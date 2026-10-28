package cn.exam.sentry.lab

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.exam.sentry.core.EventEntity
import cn.exam.sentry.core.SentryRuntime
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ActionCheck(
    val title: String, val guide: String, val probe: String? = null, val minApi: Int = 26,
    val matches: (EventEntity) -> Boolean,
)

private fun EventEntity.flag(name: String): Boolean = runCatching { JSONObject(detail).optBoolean(name) }.getOrDefault(false)

private val actionChecks = listOf(
    ActionCheck("切屏后返回", "切到其他应用，停留超过 3 秒，再返回。使用情况访问可补充去向；系统授权弹窗和允许应用单独处理。") { it.kind == "APP_RETURN" },
    ActionCheck("系统截图", "关闭画面保护，在本 App 前台用实体按键截图。只观察系统截图回调，不读取截图文件；保护开启时无回调不能证明没有尝试。", "activity.screenshot", 34) { it.kind == "SCREEN_CAPTURED" },
    ActionCheck("录屏画面可见", "关闭画面保护，用系统开始录屏并回到本 App。仅当系统明确报告本 App 在录制画面中可见才计入。", "activity.recording", 35) { it.kind == "SCREEN_RECORDING" && it.flag("visibleInRecording") },
    ActionCheck("分屏 / 多窗口", "从系统最近任务进入分屏。厂商小窗不一定触发标准回调，可选无障碍可提供补充线索。", "activity.multi_window") { it.kind == "MULTI_WINDOW_CHANGED" && it.flag("inMultiWindow") },
    ActionCheck("触摸遮挡", "在本 App 上方有可见悬浮窗口时触摸本 App。系统可能拦截触摸或隐藏覆盖层，没有标记不能证明没有遮挡。") { it.kind == "WINDOW_OBSCURED" },
    ActionCheck("复制 / 剪切", "在上方文本操作区长按选中文字，用系统菜单复制或剪切。仅记录动作和长度，不保存文本。") { it.kind in setOf("COPY", "CUT") },
    ActionCheck("真实粘贴", "在文本区用系统菜单粘贴。输入法直接插入不一定产生粘贴回调，可在浏览器专项再次操作。") { it.kind == "PASTE" },
    ActionCheck("长按 / 文本选择", "长按文本，拖动选择范围。浏览器和原生文本区都能提供操作事件。") { it.kind in setOf("LONG_PRESS", "TEXT_SELECTION", "SELECTION") },
    ActionCheck("屏幕键盘", "点击原生文本区呼出键盘，再收起。只计算系统报告可见的状态。", "activity.window") { it.kind == "KEYBOARD_CHANGED" && it.flag("visible") },
    ActionCheck("屏幕配置 / 方向", "在系统允许自动旋转时转动设备，或进入支持的横屏窗口，观察真实配置变化。系统配置回调也可能包含字体、语言等变化，以事件详情为准。") { it.kind == "CONFIGURATION_CHANGED" || it.kind == "BROWSER_ORIENTATION" },
    ActionCheck("浏览器滚动 / 缩放", "进入浏览器专项，滑动页面或双指缩放。滚动停止后记录最终位置，页面预览不冒充系统检测。") { it.kind in setOf("BROWSER_SCROLL", "WEB_ZOOM") },
    ActionCheck("双页面会话", "进入浏览器专项，点击右上角加号打开对照页。两个真实页面需收到彼此广播后才计入。") { it.kind == "DUPLICATE_PAGE" },
    ActionCheck("会话水印匹配", "在浏览器专项复制带水印样本，再粘贴到核验区并执行核验。只匹配已取得的本地文本。") { it.kind == "WATERMARK_CHECK" && it.flag("matches") },
)

@Composable
fun PracticalChecks(sessionId: String?) {
    val state by SentryRuntime.state.collectAsStateWithLifecycle()
    var expanded by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    val events = remember(state.events, sessionId) { state.events.filter { it.sessionId == sessionId && it.isTest } }
    val captured = remember(events) { actionChecks.map { check -> events.filter(check.matches) } }
    val colors = MaterialTheme.colorScheme
    val count = captured.count { it.isNotEmpty() }
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = colors.outlineVariant)
    Text("操作检测指引", style = MaterialTheme.typography.titleMedium)
    Text("近 ${events.size} 条实测事件中，已观察到 $count / ${actionChecks.size} 类动作。点开项目查看操作方法与来源。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    actionChecks.forEachIndexed { index, check ->
        val matches = captured[index]
        val probe = state.probes.firstOrNull { it.id == check.probe }
        val status = when {
            matches.isNotEmpty() -> "已捕获 ${matches.size} 次"
            Build.VERSION.SDK_INT < check.minApi -> "系统不支持"
            probe?.status == "PERMISSION_REQUIRED" -> "需要授权"
            probe?.status in setOf("ERROR", "STALE", "LIMITED", "UNSUPPORTED", "DISABLED") -> "探针受限"
            else -> "未观察到"
        }
        Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == check.title) null else check.title }.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(check.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(status, style = MaterialTheme.typography.labelMedium, color = if (matches.isNotEmpty()) colors.primary else colors.onSurfaceVariant)
            }
            if (expanded == check.title) {
                Text(check.guide, style = MaterialTheme.typography.bodyMedium)
                if (Build.VERSION.SDK_INT < check.minApi) Text("此项要求 Android API ${check.minApi} 或更新。", style = MaterialTheme.typography.bodySmall)
                probe?.let { Text("探针：${it.label} · ${it.detail}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                matches.firstOrNull()?.let { event ->
                    Text("最近捕获：${SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(event.wallTime))} · ${event.source} · #${event.seq}\n${event.summary}", style = MaterialTheme.typography.bodySmall, color = colors.primary)
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
    }
    Text("这里只汇总最近 600 条事件，完整记录请导出报告。未观察到不代表环境安全，也不代表操作失败；实测捕获本身不是作弊结论。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
}
