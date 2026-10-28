package cn.exam.sentry.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest

internal class SileroVad(context: Context) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val sampleRateTensor: OnnxTensor
    private val state = FloatArray(256)
    private val contextSamples = FloatArray(64)

    init {
        val model = context.assets.open("models/silero_vad_v6.2.onnx").use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) }
        check(digest == MODEL_SHA256) { "本地语音模型完整性检查未通过" }
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            session = environment.createSession(model, options)
        }
        sampleRateTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(16_000L)), longArrayOf())
        check(session.inputNames.containsAll(setOf("input", "state", "sr"))) { "本地语音模型接口不兼容" }
    }

    fun probability(pcm: ShortArray): Float {
        require(pcm.size == FRAME_SAMPLES)
        val input = FloatArray(576)
        contextSamples.copyInto(input)
        for (index in pcm.indices) input[index + 64] = pcm[index] / 32768f
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 576)).use { inputTensor ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { stateTensor ->
                session.run(mapOf("input" to inputTensor, "state" to stateTensor, "sr" to sampleRateTensor)).use { result ->
                    val probability = (result[0] as OnnxTensor).floatBuffer.get(0)
                    (result[1] as OnnxTensor).floatBuffer.get(state)
                    input.copyInto(contextSamples, 0, input.size - contextSamples.size)
                    check(probability.isFinite() && probability in 0f..1f) { "语音模型返回了无效结果" }
                    return probability
                }
            }
        }
    }

    fun reset() {
        state.fill(0f)
        contextSamples.fill(0f)
    }

    override fun close() {
        sampleRateTensor.close()
        session.close()
    }

    companion object {
        const val FRAME_SAMPLES = 512
        const val MODEL_SHA256 = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
    }
}
