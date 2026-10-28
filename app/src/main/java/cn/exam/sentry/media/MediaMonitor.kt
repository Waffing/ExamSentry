package cn.exam.sentry.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import cn.exam.sentry.core.SentryRuntime
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max

data class MediaState(
    val cameraRunning: Boolean = false,
    val audioRunning: Boolean = false,
    val faceCount: Int? = null,
    val speechProbability: Float? = null,
    val quality: String = "未开启",
    val error: String? = null
)

class MediaMonitor(context: Context) {
    private val app = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(app)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(MediaState())
    val state: StateFlow<MediaState> = mutableState.asStateFlow()
    private val cameraGeneration = AtomicInteger()
    private val audioGeneration = AtomicInteger()
    private val audioRequested = AtomicBoolean()
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var detector: FaceDetector? = null
    private var cameraExecutor: ExecutorService? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var cameraError: String? = null
    @Volatile private var audioError: String? = null
    @Volatile private var lastFrameAt = 0L
    private var absentSince: Long? = null
    private var multipleSince: Long? = null
    private var absentReported = false
    private var multipleReported = false
    private var lowQualityReported = false
    private var cameraRequestedAt = 0L
    private var cameraStale = false
    private val watchdog = object : Runnable {
        override fun run() {
            if (analysis == null) return
            val last = if (lastFrameAt > 0) lastFrameAt else cameraRequestedAt
            if (last > 0 && SystemClock.elapsedRealtime() - last > 4_000 && !cameraStale) {
                cameraStale = true
                resetFaceTimers()
                mutableState.update { it.copy(faceCount = null, quality = "画面信号中断，无法判断") }
                SentryRuntime.probe("camera_faces", "本地人脸辅助", "STALE", "超过 4 秒未收到有效相机帧")
                SentryRuntime.emit("MEDIA_UNAVAILABLE", "CAMERAX", "相机画面信号中断", json("detector" to "camera_faces"))
            }
            mainHandler.postDelayed(this, 1_000)
        }
    }
    private var cameraOwner: LifecycleOwner? = null
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) stopAll()
    }

    init {
        (context as? LifecycleOwner)?.let { owner ->
            cameraOwner = owner
            owner.lifecycle.addObserver(lifecycleObserver)
        }
        SentryRuntime.probe("camera_faces", "本地人脸辅助", "DISABLED", "未开启；仅分析本 App 前台画面")
        SentryRuntime.probe("audio_vad", "本地语音活动", "DISABLED", "未开启；不保存原始音频")
    }

    fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (!granted(Manifest.permission.CAMERA)) {
            failCamera("相机权限未授予", "PERMISSION_REQUIRED")
            return
        }
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            failCamera("应用不在前台，相机检测未启动", "LIMITED")
            return
        }
        stopCamera()
        val generation = cameraGeneration.incrementAndGet()
        cameraError = null
        publishError()
        if (cameraOwner !== lifecycleOwner) {
            cameraOwner?.lifecycle?.removeObserver(lifecycleObserver)
            cameraOwner = lifecycleOwner
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        }
        SentryRuntime.probe("camera_faces", "本地人脸辅助", "AVAILABLE", "正在初始化前置相机与本地模型")
        val providerFuture = ProcessCameraProvider.getInstance(app)
        providerFuture.addListener({
            if (generation != cameraGeneration.get()) return@addListener
            try {
                val provider = providerFuture.get()
                if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    failCamera("未找到可用的前置相机", "UNSUPPORTED")
                    return@addListener
                }
                MlKit.initialize(app)
                val faceDetector = FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setMinFaceSize(0.12f)
                        .build()
                )
                val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sentry-camera") }
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                detector = faceDetector
                cameraExecutor = executor
                cameraProvider = provider
                preview = cameraPreview
                analysis = imageAnalysis
                imageAnalysis.setAnalyzer(executor) { image -> analyze(image, generation, faceDetector) }
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, cameraPreview, imageAnalysis)
                cameraRequestedAt = SystemClock.elapsedRealtime()
                mainHandler.postDelayed(watchdog, 1_000)
                mutableState.update { it.copy(cameraRunning = true, quality = "等待有效画面", faceCount = null) }
                SentryRuntime.probe("camera_faces", "本地人脸辅助", "ACTIVE", "前置相机已连接；画面合格时检测人数")
                SentryRuntime.emit("CAMERA_STARTED", "CAMERAX", "已开启本地人脸辅助检测")
            } catch (error: Exception) {
                if (generation == cameraGeneration.get()) {
                    releaseCamera()
                    failCamera("相机或本地人脸模型不可用（${error.javaClass.simpleName}）", "ERROR")
                }
            }
        }, mainExecutor)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(image: ImageProxy, generation: Int, faceDetector: FaceDetector) {
        val now = SystemClock.elapsedRealtime()
        if (generation != cameraGeneration.get() || now - lastFrameAt < 500) {
            image.close()
            return
        }
        lastFrameAt = now
        val quality = qualityOf(image)
        if (quality != null) {
            mainExecutor.execute {
                if (generation == cameraGeneration.get()) {
                    resetFaceTimers()
                    mutableState.update { it.copy(faceCount = null, quality = quality) }
                    SentryRuntime.probe("camera_faces", "本地人脸辅助", "LIMITED", "$quality；人数无法判断")
                    if (!lowQualityReported) {
                        lowQualityReported = true
                        SentryRuntime.emit("MEDIA_UNAVAILABLE", "CAMERAX", "画面质量不足，人脸检测无法判断", json("reason" to quality), 0)
                    }
                }
            }
            image.close()
            return
        }
        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            return
        }
        faceDetector.process(InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees))
            .addOnSuccessListener(mainExecutor) { faces ->
                if (generation == cameraGeneration.get()) onFaces(faces.size, now)
            }
            .addOnFailureListener(mainExecutor) { error ->
                if (generation == cameraGeneration.get()) {
                    resetFaceTimers()
                    mutableState.update { it.copy(faceCount = null, quality = "模型暂不可用") }
                    if (cameraError == null) failCamera("人脸模型推理失败（${error.javaClass.simpleName}）", "ERROR")
                }
            }
            .addOnCompleteListener(mainExecutor) { image.close() }
    }

    private fun onFaces(count: Int, now: Long) {
        if (cameraStale) {
            cameraStale = false
            resetFaceTimers()
            SentryRuntime.emit("MEDIA_RESTORED", "CAMERAX", "相机画面输入恢复")
        }
        lowQualityReported = false
        cameraError = null
        publishError()
        mutableState.update { it.copy(cameraRunning = true, faceCount = count, quality = "画面可用") }
        SentryRuntime.probe("camera_faces", "本地人脸辅助", "ACTIVE", "画面可用；检测到 $count 个人脸；仅为本地模型估计")
        when {
            count == 0 -> {
                multipleSince = null
                multipleReported = false
                if (absentSince == null) absentSince = now
                val duration = now - (absentSince ?: now)
                if (duration >= 10_000 && !absentReported) {
                    absentReported = true
                    SentryRuntime.emit("FACE_ABSENT", "MLKIT_LOCAL", "有效画面中持续未检测到人脸", json("durationMs" to duration, "thresholdMs" to 10_000), 1)
                }
            }
            count > 1 -> {
                absentSince = null
                absentReported = false
                if (multipleSince == null) multipleSince = now
                val duration = now - (multipleSince ?: now)
                if (duration >= 3_000 && !multipleReported) {
                    multipleReported = true
                    SentryRuntime.emit("FACE_MULTIPLE", "MLKIT_LOCAL", "有效画面中持续检测到多个人脸", json("faceCount" to count, "durationMs" to duration, "thresholdMs" to 3_000), 1)
                }
            }
            else -> {
                if (absentReported || multipleReported) {
                    SentryRuntime.emit("FACE_RESTORED", "MLKIT_LOCAL", "画面恢复为单个人脸", json("faceCount" to count))
                }
                resetFaceTimers()
            }
        }
    }

    private fun qualityOf(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return "缺少亮度画面"
        val buffer = plane.buffer
        val crop = image.cropRect
        var sum = 0.0
        var sumSquares = 0.0
        var gradient = 0.0
        var count = 0
        for (row in 0 until 24) {
            val y = crop.top + row * max(1, crop.height() - 1) / 23
            var previous = -1
            for (column in 0 until 32) {
                val x = crop.left + column * max(1, crop.width() - 1) / 31
                val index = y * plane.rowStride + x * plane.pixelStride
                if (index >= buffer.limit()) continue
                val value = buffer.get(index).toInt() and 255
                sum += value
                sumSquares += value * value
                if (previous >= 0) gradient += abs(value - previous)
                previous = value
                count++
            }
        }
        if (count == 0) return "画面采样不可用"
        val mean = sum / count
        val variance = sumSquares / count - mean * mean
        return when {
            mean < 28 -> "画面过暗或镜头被遮挡"
            mean > 240 -> "画面过曝"
            variance < 40 && gradient / count < 2 -> "画面细节不足，无法可靠判断"
            else -> null
        }
    }

    fun stopCamera() {
        cameraGeneration.incrementAndGet()
        val wasRunning = mutableState.value.cameraRunning
        releaseCamera()
        resetFaceTimers()
        lastFrameAt = 0
        lowQualityReported = false
        cameraError = null
        mutableState.update { it.copy(cameraRunning = false, faceCount = null, quality = "未开启") }
        publishError()
        SentryRuntime.probe("camera_faces", "本地人脸辅助", "DISABLED", "未开启；仅在本 App 前台经用户开启后分析")
        if (wasRunning) SentryRuntime.emit("CAMERA_STOPPED", "CAMERAX", "本地人脸辅助检测已停止")
    }

    private fun releaseCamera() {
        mainHandler.removeCallbacks(watchdog)
        cameraRequestedAt = 0L
        cameraStale = false
        analysis?.clearAnalyzer()
        val useCases = listOfNotNull(preview, analysis).toTypedArray()
        if (useCases.isNotEmpty()) cameraProvider?.unbind(*useCases)
        preview = null
        analysis = null
        detector?.close()
        detector = null
        cameraExecutor?.shutdown()
        cameraExecutor = null
    }

    private fun resetFaceTimers() {
        absentSince = null
        multipleSince = null
        absentReported = false
        multipleReported = false
    }

    fun startAudio() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            failAudio("麦克风权限未授予", "PERMISSION_REQUIRED")
            return
        }
        if (cameraOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == false) {
            failAudio("应用不在前台，语音检测未启动", "LIMITED")
            return
        }
        if (!audioRequested.compareAndSet(false, true)) return
        val generation = audioGeneration.incrementAndGet()
        audioError = null
        publishError()
        SentryRuntime.probe("audio_vad", "本地语音活动", "AVAILABLE", "正在初始化本地 Silero VAD 模型")
        Thread({ captureAudio(generation) }, "sentry-audio").start()
    }

    private fun captureAudio(generation: Int) {
        var audioRecord: AudioRecord? = null
        var callback: AudioManager.AudioRecordingCallback? = null
        val silenced = AtomicBoolean(false)
        try {
            SileroVad(app).use { vad ->
                val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "当前设备不支持 16 kHz 音频输入" }
                val format = AudioFormat.Builder().setSampleRate(16_000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                val localRecorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(format).setBufferSizeInBytes(max(minimum, 8_192)).build()
                audioRecord = localRecorder
                if (generation != audioGeneration.get() || !audioRequested.get()) return
                recorder = localRecorder
                check(localRecorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                if (Build.VERSION.SDK_INT >= 29) {
                    callback = object : AudioManager.AudioRecordingCallback() {
                        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                            silenced.set(configs.any { it.clientAudioSessionId == localRecorder.audioSessionId && it.isClientSilenced })
                        }
                    }
                    localRecorder.registerAudioRecordingCallback(mainExecutor, callback)
                }
                localRecorder.startRecording()
                check(localRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风未进入录制状态" }
                if (generation != audioGeneration.get() || !audioRequested.get()) return
                if (Build.VERSION.SDK_INT >= 29) silenced.set(localRecorder.activeRecordingConfiguration?.isClientSilenced == true)
                mutableState.update { it.copy(audioRunning = true, speechProbability = null) }
                SentryRuntime.probe("audio_vad", "本地语音活动", "ACTIVE", "Silero VAD 本机推理；仅保存语音活动统计")
                SentryRuntime.emit("AUDIO_STARTED", "SILERO_LOCAL", "已开启本地语音活动分析")
                val samples = ShortArray(SileroVad.FRAME_SAMPLES)
                val slices = ArrayDeque<SpeechSlice>()
                var zeroReads = 0
                var offset = 0
                var lastUiAt = 0L
                var lastSpeechAt = 0L
                var episodeReported = false
                var unavailableReported = false
                while (generation == audioGeneration.get() && audioRequested.get()) {
                    val read = localRecorder.read(samples, offset, samples.size - offset, AudioRecord.READ_BLOCKING)
                    if (generation != audioGeneration.get() || !audioRequested.get()) break
                    if (read < 0) error("麦克风读取失败，错误码 $read")
                    if (read == 0) {
                        zeroReads++
                        if (zeroReads >= 10) error("麦克风未返回有效音频帧")
                        continue
                    }
                    zeroReads = 0
                    offset += read
                    if (offset < samples.size) continue
                    offset = 0
                    val now = SystemClock.elapsedRealtime()
                    if (silenced.get()) {
                        slices.clear()
                        vad.reset()
                        episodeReported = false
                        mutableState.update { it.copy(audioRunning = false, speechProbability = null) }
                        if (!unavailableReported) {
                            unavailableReported = true
                            failAudio("麦克风被系统静音，语音活动无法判断", "LIMITED")
                        }
                        continue
                    }
                    if (unavailableReported) {
                        unavailableReported = false
                        audioError = null
                        publishError()
                        mutableState.update { it.copy(audioRunning = true) }
                        SentryRuntime.probe("audio_vad", "本地语音活动", "ACTIVE", "麦克风输入恢复；本地模型正在分析")
                        SentryRuntime.emit("MEDIA_RESTORED", "AUDIORECORD", "麦克风输入恢复")
                    }
                    val probability = vad.probability(samples)
                    val speech = probability >= 0.5f
                    if (speech) lastSpeechAt = now
                    slices.addLast(SpeechSlice(now, speech))
                    while (slices.isNotEmpty() && slices.first.time <= now - 10_000) slices.removeFirst()
                    val speechMs = slices.count { it.speech } * 32L
                    if (speechMs >= 5_000 && !episodeReported) {
                        episodeReported = true
                        SentryRuntime.emit("SPEECH_ACTIVITY", "SILERO_LOCAL", "近 10 秒内检测到持续语音活动", json("speechMs" to speechMs, "windowMs" to 10_000, "thresholdMs" to 5_000, "speakerKnown" to false), 1)
                    }
                    if (episodeReported && now - lastSpeechAt >= 2_000) {
                        episodeReported = false
                        slices.clear()
                        SentryRuntime.emit("SPEECH_RESTORED", "SILERO_LOCAL", "持续语音活动已结束")
                    }
                    if (now - lastUiAt >= 500) {
                        lastUiAt = now
                        mutableState.update { it.copy(speechProbability = probability, audioRunning = true) }
                        SentryRuntime.probe("audio_vad", "本地语音活动", "ACTIVE", "本地模型运行中；近 10 秒语音活动 ${speechMs / 1_000.0} 秒")
                    }
                }
            }
        } catch (error: Exception) {
            if (generation == audioGeneration.get() && audioRequested.get()) {
                failAudio("语音分析不可用（${error.message ?: error.javaClass.simpleName}）", "ERROR")
            }
        } finally {
            val localRecorder = audioRecord
            if (localRecorder != null) {
                if (Build.VERSION.SDK_INT >= 29 && callback != null) runCatching { localRecorder.unregisterAudioRecordingCallback(callback) }
                if (localRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { localRecorder.stop() }
                localRecorder.release()
            }
            if (recorder === localRecorder) recorder = null
            if (generation == audioGeneration.get()) {
                audioRequested.set(false)
                mutableState.update { it.copy(audioRunning = false, speechProbability = null) }
            }
        }
    }

    fun stopAudio() {
        val wasRequested = audioRequested.getAndSet(false)
        audioGeneration.incrementAndGet()
        recorder?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { it.stop() } }
        audioError = null
        mutableState.update { it.copy(audioRunning = false, speechProbability = null) }
        publishError()
        SentryRuntime.probe("audio_vad", "本地语音活动", "DISABLED", "未开启；不保存原始音频")
        if (wasRequested) SentryRuntime.emit("AUDIO_STOPPED", "AUDIORECORD", "本地语音活动分析已停止")
    }

    fun stopAll() {
        stopCamera()
        stopAudio()
    }

    private fun failCamera(message: String, status: String) {
        cameraError = message
        resetFaceTimers()
        mutableState.update { it.copy(cameraRunning = false, faceCount = null, quality = "无法判断") }
        publishError()
        SentryRuntime.probe("camera_faces", "本地人脸辅助", status, message)
        SentryRuntime.emit("MEDIA_UNAVAILABLE", "CAMERAX", message, json("detector" to "camera_faces"))
    }

    private fun failAudio(message: String, status: String) {
        audioError = message
        mutableState.update { it.copy(audioRunning = false, speechProbability = null) }
        publishError()
        SentryRuntime.probe("audio_vad", "本地语音活动", status, message)
        SentryRuntime.emit("MEDIA_UNAVAILABLE", "AUDIORECORD", message, json("detector" to "audio_vad"))
    }

    private fun publishError() {
        val errors = listOfNotNull(cameraError, audioError)
        mutableState.update { it.copy(error = errors.takeIf { messages -> messages.isNotEmpty() }?.joinToString("\n")) }
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private fun json(vararg entries: Pair<String, Any>): String = JSONObject().apply { entries.forEach { put(it.first, it.second) } }.toString()

    private data class SpeechSlice(val time: Long, val speech: Boolean)
}
