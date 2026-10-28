package cn.exam.sentry.detectors

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import cn.exam.sentry.core.SentryRuntime
import org.json.JSONArray
import org.json.JSONObject

class WindowMetadataService : AccessibilityService() {
    private var lastSnapshotElapsed = 0L
    private var lastSnapshot = ""
    private var sampledSession = ""
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            if (SentryRuntime.state.value.activeSession != null) sampleWindows(null)
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        SentryRuntime.init(applicationContext)
        connected = true
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        SentryRuntime.probe("accessibility.windows", "跨应用窗口元数据", "ACTIVE", "服务已连接；仅在监测会话中处理窗口类型、边界和所属应用，不读取文字")
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || SentryRuntime.state.value.activeSession == null) return
        sampleWindows(event)
    }

    private fun sampleWindows(event: AccessibilityEvent?) {
        val session = SentryRuntime.state.value.activeSession ?: return
        if (sampledSession != session.id) {
            sampledSession = session.id
            lastSnapshot = ""
            lastSnapshotElapsed = 0
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        try {
            val items = windows.map { window ->
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                val root = window.root
                val pkg = root?.packageName?.toString()
                @Suppress("DEPRECATION")
                root?.recycle()
                JSONObject().put("type", window.type).put("package", pkg ?: JSONObject.NULL)
                    .put("focused", window.isFocused).put("active", window.isActive)
                    .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            }.sortedBy { it.toString() }
            val packageName = event?.packageName?.toString()
            if (event != null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !packageName.isNullOrBlank() && !PermissionTools.isTransientPackage(this, packageName)) {
                val eventElapsed = nowElapsed - (SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0)
                val hasImeWindow = items.any { it.getInt("type") == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.optString("package") == packageName }
                val activeApplication = items.any { it.getInt("type") == AccessibilityWindowInfo.TYPE_APPLICATION && it.optBoolean("active") && it.optString("package") == packageName }
                val imeClass = event.className?.toString()?.contains("InputMethod", true) == true
                if (activeApplication && !hasImeWindow && !imeClass) {
                    ForegroundEvidence.offer(packageName, "ACCESSIBILITY", eventElapsed, System.currentTimeMillis() - (nowElapsed - eventElapsed))
                }
            } else if (event == null) {
                items.firstOrNull { it.getInt("type") == AccessibilityWindowInfo.TYPE_APPLICATION && it.optBoolean("active") && it.optBoolean("focused") }
                    ?.optString("package")?.takeIf { it.isNotBlank() && it != "null" && !PermissionTools.isTransientPackage(this, it) }
                    ?.let { ForegroundEvidence.offer(it, "ACCESSIBILITY_SNAPSHOT") }
            }
            if (nowElapsed - lastSnapshotElapsed < 1_000) return
            lastSnapshotElapsed = nowElapsed
            if (items.isEmpty()) {
                SentryRuntime.probe("accessibility.windows", "跨应用窗口元数据", "LIMITED", "系统本次未提供可见窗口；不能确认是否存在小窗或覆盖层")
                return
            }
            val snapshot = JSONArray(items).toString()
            SentryRuntime.probe("accessibility.windows", "跨应用窗口元数据", "ACTIVE", "当前取得 ${items.size} 个窗口的元数据；系统可能隐藏部分窗口")
            if (snapshot != lastSnapshot) {
                lastSnapshot = snapshot
                SentryRuntime.emit("WINDOW_METADATA_CHANGED", "ACCESSIBILITY", "系统可见窗口集合发生变化", JSONObject().put("windows", JSONArray(items)).toString())
                val overlays = items.filter { it.getInt("type") == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                if (overlays.isNotEmpty()) {
                    SentryRuntime.emit("WINDOW_OVERLAY", "ACCESSIBILITY", "系统窗口列表中存在辅助服务覆盖窗口", JSONObject().put("windows", JSONArray(overlays)).toString(), 1)
                }
                val applicationPackages = items.filter { it.getInt("type") == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .mapNotNull { it.optString("package").takeIf { value -> value.isNotBlank() && value != "null" } }.distinct()
                if (applicationPackages.size > 1) {
                    SentryRuntime.emit("WINDOW_MULTI_APP", "ACCESSIBILITY", "可见窗口包含多个应用；可能为分屏或小窗", JSONObject().put("packages", JSONArray(applicationPackages)).toString(), 1)
                }
            }
        } catch (_: SecurityException) {
            SentryRuntime.probe("accessibility.windows", "跨应用窗口元数据", "LIMITED", "系统拒绝提供部分窗口信息；窗口检测结果不完整")
        } catch (error: RuntimeException) {
            SentryRuntime.probe("accessibility.windows", "跨应用窗口元数据", "ERROR", "窗口元数据读取失败：${error.javaClass.simpleName}")
        }
    }

    override fun onInterrupt() {
        SentryRuntime.probe("accessibility.windows", "跨应用窗口元数据", "LIMITED", "系统中断了辅助服务反馈；跨应用窗口采集可能不连续")
    }

    override fun onDestroy() {
        connected = false
        handler.removeCallbacks(refresh)
        SentryRuntime.probe("accessibility.windows", "跨应用窗口元数据", "PERMISSION_REQUIRED", "服务连接已断开；跨应用窗口状态未知")
        if (SentryRuntime.state.value.activeSession != null) SentryRuntime.emit("DETECTOR_DISCONNECTED", "ACCESSIBILITY", "窗口元数据服务断开", severity = 1)
        super.onDestroy()
    }

    companion object {
        @Volatile var connected = false
            private set
    }
}
