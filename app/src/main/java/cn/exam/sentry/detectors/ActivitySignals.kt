package cn.exam.sentry.detectors

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cn.exam.sentry.core.SentryRuntime
import org.json.JSONObject
import java.util.function.Consumer

class ActivitySignals(private val activity: ComponentActivity) {
    private var capture: AutoCloseable? = null
    private var recording: AutoCloseable? = null
    private var started = false
    private var observedSession: String? = null
    private var protected = false
    private var lastLayout = ""
    private var lastKeyboard: Boolean? = null
    private var lastObscured = 0L
    private var lastFocus: Boolean? = null
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { inspectLayout() }

    fun onStart() {
        if (started) return
        started = true
        refreshForSession()
    }

    fun refreshForSession() {
        if (!started) return
        val session = SentryRuntime.state.value.activeSession
        if (session == null) {
            if (observedSession != null) {
                unregisterSignals()
                listOf("screenshot" to "本 App 截图", "recording" to "本 App 被录制状态", "window" to "本 App 窗口与键盘", "multi_window" to "本 App 多窗口").forEach { (id, label) ->
                    SentryRuntime.probe("activity.$id", label, "DISABLED", "会话已结束，等待主动开始监测")
                }
            }
            return
        }
        if (observedSession == session.id) return
        unregisterSignals()
        observedSession = session.id
        activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        registerCaptureSignals()
        lastLayout = ""
        lastKeyboard = null
        lastFocus = null
        inspectLayout()
        onMultiWindowModeChanged(activity.isInMultiWindowMode)
        if (activity.hasWindowFocus()) {
            SentryRuntime.emit("APP_FOREGROUND", "ACTIVITY", "会话开始时，本 App 位于前台")
            ForegroundEvidence.offer(activity.packageName, "ACTIVITY")
        }
    }

    private fun registerCaptureSignals() {
        capture?.close()
        recording?.close()
        capture = null
        recording = null
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                capture = ScreenCaptureRegistration(activity)
                SentryRuntime.probe("activity.screenshot", "本 App 截图", "ACTIVE", screenshotDetail())
            } catch (error: RuntimeException) {
                SentryRuntime.probe("activity.screenshot", "本 App 截图", "ERROR", "系统回调注册失败：${error.javaClass.simpleName}")
            }
        } else {
            SentryRuntime.probe("activity.screenshot", "本 App 截图", "UNSUPPORTED", "系统截图回调要求 Android 14 或更新；不监测截图文件夹")
        }
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                recording = ScreenRecordingRegistration(activity)
            } catch (error: RuntimeException) {
                SentryRuntime.probe("activity.recording", "本 App 被录制状态", "ERROR", "系统回调注册失败：${error.javaClass.simpleName}")
            }
        } else {
            SentryRuntime.probe("activity.recording", "本 App 被录制状态", "UNSUPPORTED", "系统录屏可见性回调要求 Android 15 或更新")
        }
    }

    fun onStop() {
        if (!started) return
        started = false
        val wasObserving = observedSession != null
        unregisterSignals()
        if (!wasObserving || SentryRuntime.state.value.activeSession == null) return
        SentryRuntime.emit("APP_BACKGROUND", "ACTIVITY", "检测界面离开前台；去向由可用探针确认")
        if (Build.VERSION.SDK_INT >= 34) SentryRuntime.probe("activity.screenshot", "本 App 截图", "LIMITED", "本 App 当前不在可见检测范围；不能据此判断其他 App 是否被截图")
        if (Build.VERSION.SDK_INT >= 35) SentryRuntime.probe("activity.recording", "本 App 被录制状态", "LIMITED", "本 App 当前不在可见检测范围；全机录屏状态未知")
    }

    private fun unregisterSignals() {
        activity.window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        capture?.close()
        recording?.close()
        capture = null
        recording = null
        observedSession = null
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) PermissionTools.endSystemPermissionFlow()
        if (observedSession == null || SentryRuntime.state.value.activeSession == null) return
        if (lastFocus == hasFocus) return
        lastFocus = hasFocus
        SentryRuntime.emit(if (hasFocus) "WINDOW_FOCUS_GAINED" else "WINDOW_FOCUS_LOST", "ACTIVITY", if (hasFocus) "本 App 窗口获得焦点" else "本 App 窗口失去焦点；可能是系统弹窗或键盘")
        if (hasFocus) {
            ForegroundEvidence.offer(activity.packageName, "ACTIVITY")
        }
    }

    fun onConfigurationChanged(configuration: Configuration) {
        if (observedSession == null || SentryRuntime.state.value.activeSession == null) return
        SentryRuntime.emit("CONFIGURATION_CHANGED", "ACTIVITY", "屏幕配置发生变化", JSONObject().put("orientation", if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait")
            .put("densityDpi", configuration.densityDpi).put("fontScale", configuration.fontScale.toDouble()).toString())
        inspectLayout()
    }

    fun onMultiWindowModeChanged(inMultiWindow: Boolean) {
        if (observedSession == null || SentryRuntime.state.value.activeSession == null) return
        SentryRuntime.emit("MULTI_WINDOW_CHANGED", "SYSTEM", if (inMultiWindow) "本 App 进入分屏或多窗口" else "本 App 退出多窗口", JSONObject().put("inMultiWindow", inMultiWindow).toString(), if (inMultiWindow) 1 else 0)
        SentryRuntime.probe("activity.multi_window", "本 App 多窗口", "ACTIVE", if (inMultiWindow) "系统确认当前处于多窗口模式" else "系统当前未报告本 App 处于多窗口模式")
    }

    fun onPictureInPictureModeChanged(inPictureInPicture: Boolean) {
        if (observedSession == null || SentryRuntime.state.value.activeSession == null) return
        SentryRuntime.emit("PICTURE_IN_PICTURE_CHANGED", "SYSTEM", if (inPictureInPicture) "本 App 进入画中画" else "本 App 退出画中画", JSONObject().put("inPictureInPicture", inPictureInPicture).toString(), if (inPictureInPicture) 1 else 0)
    }

    fun onTouchEvent(event: MotionEvent) {
        if (observedSession == null || SentryRuntime.state.value.activeSession == null) return
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return
        val obscured = event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0
        val partial = Build.VERSION.SDK_INT >= 29 && event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0
        val now = android.os.SystemClock.elapsedRealtime()
        if ((obscured || partial) && now - lastObscured > 5_000) {
            lastObscured = now
            SentryRuntime.emit("WINDOW_OBSCURED", "SYSTEM", "本 App 的触摸事件带有窗口遮挡标记", JSONObject().put("obscured", obscured).put("partiallyObscured", partial).toString(), 1)
        }
    }

    fun setProtection(enabled: Boolean) {
        val changed = protected != enabled
        protected = enabled
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) activity.window.setHideOverlayWindows(enabled)
        if (!changed && SentryRuntime.state.value.activeSession == null) return
        SentryRuntime.probe("activity.protection", "本 App 画面保护", if (enabled) "ACTIVE" else "DISABLED", if (enabled) "已请求系统防截屏与录屏；支持的版本同时隐藏外部覆盖窗口。阻止操作不等于捕获到尝试事件。" else "保护未开启，可在实测区验证系统截图与录屏回调")
        if (observedSession != null && SentryRuntime.state.value.activeSession != null && Build.VERSION.SDK_INT >= 34) SentryRuntime.probe("activity.screenshot", "本 App 截图", "ACTIVE", screenshotDetail())
        SentryRuntime.emit("PROTECTION_CHANGED", "USER", if (enabled) "已开启本 App 画面保护" else "已关闭本 App 画面保护", JSONObject().put("enabled", enabled).toString())
    }

    fun close() = onStop()

    private fun screenshotDetail() = if (protected) "系统截图回调已注册；画面保护可能阻止截图，因此没有回调不能证明没有尝试" else "已注册系统回调；仅检测本 App 可见时系统支持的截图方式"

    private fun inspectLayout() {
        if (!started || observedSession == null || SentryRuntime.state.value.activeSession == null) return
        val decor = activity.window.decorView
        val key = "${decor.width}x${decor.height}"
        if (key != lastLayout && decor.width > 0 && decor.height > 0) {
            lastLayout = key
            SentryRuntime.emit("WINDOW_RESIZED", "ACTIVITY", "本 App 可见窗口尺寸变化", JSONObject().put("widthPx", decor.width).put("heightPx", decor.height).toString())
        }
        val keyboard = ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime())
        if (keyboard != null && keyboard != lastKeyboard) {
            lastKeyboard = keyboard
            SentryRuntime.emit("KEYBOARD_CHANGED", "SYSTEM", if (keyboard) "屏幕键盘已显示" else "屏幕键盘已隐藏", JSONObject().put("visible", keyboard).toString())
        }
        SentryRuntime.probe("activity.multi_window", "本 App 多窗口", "ACTIVE", if (activity.isInMultiWindowMode) "系统确认处于多窗口" else "系统当前未报告处于多窗口")
        SentryRuntime.probe("activity.window", "本 App 窗口与键盘", "ACTIVE", "可见窗口 $key；键盘 ${keyboard?.let { if (it) "显示" else "隐藏" } ?: "未知"}")
    }

    @RequiresApi(34)
    private class ScreenCaptureRegistration(private val activity: Activity) : AutoCloseable {
        private var closed = false
        private val callback = Activity.ScreenCaptureCallback {
            if (!closed) SentryRuntime.emit("SCREEN_CAPTURED", "SYSTEM", "系统报告本 App 可见画面被截图", severity = 1)
        }
        init { activity.registerScreenCaptureCallback(activity.mainExecutor, callback) }
        override fun close() { closed = true; activity.unregisterScreenCaptureCallback(callback) }
    }

    @RequiresApi(35)
    private class ScreenRecordingRegistration(private val activity: Activity) : AutoCloseable {
        private var previous: Int? = null
        private var closed = false
        private val callback = Consumer<Int> { state ->
            if (closed) return@Consumer
            val visible = state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE
            SentryRuntime.probe("activity.recording", "本 App 被录制状态", "ACTIVE", if (visible) "系统确认本 App 画面正在录制内容中可见" else "系统当前未报告本 App 画面在录制内容中可见；不代表全机没有录屏")
            if (previous != state) {
                previous = state
                SentryRuntime.emit("SCREEN_RECORDING", "SYSTEM", if (visible) "系统报告本 App 画面正在被录制" else "系统当前未报告本 App 出现在录制内容中", JSONObject().put("visibleInRecording", visible).toString(), if (visible) 1 else 0)
            }
        }
        init { callback.accept(activity.windowManager.addScreenRecordingCallback(activity.mainExecutor, callback)) }
        override fun close() { closed = true; activity.windowManager.removeScreenRecordingCallback(callback) }
    }
}
