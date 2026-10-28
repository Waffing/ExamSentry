package cn.exam.sentry.detectors

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import cn.exam.sentry.core.SentryRuntime
import java.util.Locale

data class AppChoice(val packageName: String, val label: String)

object PermissionTools {
    private var approvedFlowSession: String? = null

    @Synchronized
    fun beginSystemPermissionFlow() {
        val active = SentryRuntime.state.value.activeSession ?: return
        if (approvedFlowSession == active.id) return
        approvedFlowSession = active.id
        SentryRuntime.emit("APPROVED_SYSTEM_FLOW_ENTER", "USER", "进入用户主动打开的系统权限流程；此间行为另行记录")
    }

    @Synchronized
    internal fun endSystemPermissionFlow() {
        val session = approvedFlowSession ?: return
        approvedFlowSession = null
        if (SentryRuntime.state.value.activeSession?.id == session) {
            SentryRuntime.emit("APPROVED_SYSTEM_FLOW_EXIT", "ACTIVITY", "已返回检测界面，系统权限流程结束")
        }
    }

    fun usageGranted(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val result = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return result == AppOpsManager.MODE_ALLOWED
    }

    fun accessibilityGranted(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val service = it.resolveInfo.serviceInfo
            service.packageName == context.packageName && service.name == WindowMetadataService::class.java.name
        }
    }

    fun openUsageSettings(context: Context) = openSettings(context, Settings.ACTION_USAGE_ACCESS_SETTINGS)
    fun openAccessibilitySettings(context: Context) = openSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS)

    internal fun isTransientPackage(context: Context, packageName: String): Boolean {
        if (packageName == "android" || packageName == "com.android.systemui" || packageName.endsWith(".permissioncontroller")) return true
        return context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun openSettings(context: Context, action: String) {
        beginSystemPermissionFlow()
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @Suppress("DEPRECATION")
    fun launchableApps(context: Context): List<AppChoice> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0)
            .map { AppChoice(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }
}
