package cn.exam.sentry.detectors

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import cn.exam.sentry.core.SentryRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class EnvironmentScanner(context: Context) {
    private val app = context.applicationContext
    fun scan(): JSONObject {
        val sessionAtStart = SentryRuntime.state.value.activeSession?.id
        val scanElapsed = android.os.SystemClock.elapsedRealtime()
        val values = linkedMapOf<String, String>()
        fun collect(key: String, label: String, status: String = "AVAILABLE", read: () -> String) {
            try {
                val value = read()
                values[key] = value
                SentryRuntime.probe(key, label, status, value)
            } catch (error: SecurityException) {
                values[key] = "unavailable:permission"
                SentryRuntime.probe(key, label, "PERMISSION_REQUIRED", "系统未提供访问权限；未取得检测结果")
            } catch (error: RuntimeException) {
                values[key] = "unavailable:${error.javaClass.simpleName}"
                SentryRuntime.probe(key, label, "ERROR", "本次读取失败：${error.javaClass.simpleName}")
            }
        }
        val usage = PermissionTools.usageGranted(app)
        values["permission.usage"] = usage.toString()
        SentryRuntime.probe("permission.usage", "使用情况访问", if (usage) "ACTIVE" else "PERMISSION_REQUIRED", if (usage) "已授权；仅在监测会话内读取应用切换事件" else "未授权；跨应用前台状态未知")
        val accessibility = PermissionTools.accessibilityGranted(app)
        values["permission.accessibility"] = accessibility.toString()
        SentryRuntime.probe("permission.accessibility", "窗口元数据服务", if (accessibility && WindowMetadataService.connected) "ACTIVE" else if (accessibility) "LIMITED" else "DISABLED", if (accessibility && WindowMetadataService.connected) "已启用并连接；仅处理窗口类型、边界和应用标识" else if (accessibility) "系统显示已启用，但服务连接尚未确认" else "可选服务未启用；跨应用窗口线索受限")
        collect("permission.own_overlay", "本 App 悬浮窗授权", "LIMITED") {
            JSONObject().put("granted", Settings.canDrawOverlays(app)).put("meaning", "仅能确认本 App 的授权；不能枚举其他应用的悬浮窗授权").toString()
        }
        for ((permission, key, label) in listOf(
            Triple(Manifest.permission.CAMERA, "permission.camera", "摄像头授权"),
            Triple(Manifest.permission.RECORD_AUDIO, "permission.microphone", "麦克风授权")
        )) {
            val granted = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
            values[key] = granted.toString()
            SentryRuntime.probe(key, label, if (granted) "AVAILABLE" else "DISABLED", if (granted) "已授权；分析仍需单独开启" else "未授权；辅助分析不可用")
        }
        val notifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        values["permission.notifications"] = notifications.toString()
        SentryRuntime.probe("permission.notifications", "通知授权", if (notifications) "AVAILABLE" else "PERMISSION_REQUIRED", if (notifications) "可显示监测状态与异常提醒" else "通知被拒绝；系统任务管理器仍可能显示前台服务")
        collect("accessibility.services", "已启用辅助服务", "LIMITED") {
            val manager = app.getSystemService(AccessibilityManager::class.java)
            val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .map { "${it.resolveInfo.serviceInfo.packageName}/${it.resolveInfo.serviceInfo.name}" }.sorted()
            JSONArray(services).toString()
        }
        collect("environment.debugger", "调试器连接", "LIMITED") {
            JSONObject().put("connected", Debug.isDebuggerConnected()).put("waiting", Debug.waitingForDebugger()).toString()
        }
        collect("environment.developer", "开发者与 USB 调试", "LIMITED") {
            JSONObject().put("developerOptions", Settings.Global.getInt(app.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1)
                .put("adbEnabled", Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1).toString()
        }
        collect("environment.emulator", "模拟器迹象", "LIMITED") {
            val hints = mutableListOf<String>()
            if (Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator", true)) hints += "构建指纹包含模拟器标记"
            if (Build.MODEL.contains("sdk_gphone", true) || Build.MODEL.contains("emulator", true)) hints += "设备型号包含模拟器标记"
            if (Build.HARDWARE in setOf("goldfish", "ranchu", "vbox86")) hints += "模拟器硬件标记"
            JSONObject().put("hints", JSONArray(hints)).put("meaning", "启发式环境线索，不代表作弊").toString()
        }
        collect("environment.root", "Root 迹象", "LIMITED") {
            val found = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/app/Superuser.apk", "/data/adb/magisk")
                .filter { File(it).exists() }
            JSONObject().put("visibleIndicators", JSONArray(found)).put("testKeys", Build.TAGS?.contains("test-keys") == true)
                .put("meaning", "仅检查可见迹象，未执行提权操作；未命中不证明未 Root").toString()
        }
        collect("environment.network", "网络、VPN 与代理") {
            val manager = app.getSystemService(ConnectivityManager::class.java)
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
            val transports = mutableListOf<String>()
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) transports += "Wi-Fi"
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) transports += "移动网络"
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) transports += "以太网"
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) transports += "VPN"
            JSONObject().put("connected", caps != null).put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                .put("transports", JSONArray(transports)).put("proxyConfigured", !System.getProperty("http.proxyHost").isNullOrBlank() || manager.defaultProxy != null).toString()
        }
        collect("environment.audio", "可用音频接口") {
            val manager = app.getSystemService(AudioManager::class.java)
            fun devices(flag: Int) = JSONArray(manager.getDevices(flag).map { device ->
                JSONObject().put("type", audioType(device.type)).put("source", device.isSource).put("sink", device.isSink)
            }.sortedBy { it.toString() })
            JSONObject().put("inputs", devices(AudioManager.GET_DEVICES_INPUTS)).put("outputs", devices(AudioManager.GET_DEVICES_OUTPUTS))
                .put("meaning", "系统可用音频接口；不表示其他应用正在使用麦克风").toString()
        }
        collect("environment.cameras", "可用摄像头接口") {
            val cameras = app.getSystemService(CameraManager::class.java).cameraIdList
            JSONObject().put("availableInterfaceCount", cameras.size).put("meaning", "接口数量不等于物理摄像头数量或占用状态").toString()
        }
        collect("environment.displays", "显示器与投屏线索", "LIMITED") {
            val displays = app.getSystemService(DisplayManager::class.java).displays
            JSONArray(displays.map { display -> JSONObject().put("id", display.displayId).put("default", display.displayId == Display.DEFAULT_DISPLAY)
                .put("state", display.state).put("flags", display.flags) }).toString()
        }
        collect("environment.screen", "屏幕与锁屏状态") {
            JSONObject().put("interactive", app.getSystemService(PowerManager::class.java).isInteractive)
                .put("locked", app.getSystemService(KeyguardManager::class.java).isKeyguardLocked).toString()
        }
        SentryRuntime.probe("permission.other_apps", "其他应用的特殊权限", "LIMITED", "普通安装无法完整查询其他应用的悬浮窗、使用情况访问或录屏授权；此项没有自动通过结果")
        val current = values.toMap()
        synchronized(snapshotLock) {
            if (sessionAtStart != null && SentryRuntime.state.value.activeSession?.id == sessionAtStart && scanElapsed >= latestScanElapsed) {
                if (baselineSession != sessionAtStart) {
                    baselineSession = sessionAtStart
                    baseline = current
                    previous = emptyMap()
                    SentryRuntime.emit("ENVIRONMENT_BASELINE", "SYSTEM", "已建立本次环境基线", JSONObject(current).toString())
                } else {
                    for ((key, value) in current) {
                        val old = previous[key] ?: continue
                        if (old != value) {
                            val revoked = key.startsWith("permission.") && old == "true" && value == "false"
                            SentryRuntime.emit(if (revoked) "PERMISSION_REVOKED" else "ENVIRONMENT_CHANGED", "SYSTEM", if (revoked) "检测权限发生撤销" else "检测环境发生变化",
                                JSONObject().put("key", key).put("before", old).put("after", value).put("baseline", baseline[key]).toString(), if (revoked) 2 else 0)
                        }
                    }
                }
                previous = current
                latestScanElapsed = scanElapsed
            }
        }
        return JSONObject(current)
    }

    private fun audioType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙音频"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 音频"
        else -> "接口类型 $type"
    }

    companion object {
        private val snapshotLock = Any()
        private var baselineSession: String? = null
        private var baseline: Map<String, String> = emptyMap()
        private var previous: Map<String, String> = emptyMap()
        private var latestScanElapsed = 0L
    }
}
