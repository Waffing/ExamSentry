package cn.exam.sentry.lab

import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.exam.sentry.core.SentryRuntime
import org.json.JSONObject
import java.util.Locale

@Composable
fun NativeExamLab(sessionId: String?, protection: Boolean, onProtection: (Boolean) -> Unit) {
    val runtime by SentryRuntime.state.collectAsState()
    val ready = runtime.activeSession?.let { it.id == sessionId && it.mode == "LAB" } == true
    val enteredAt = remember(sessionId) { SystemClock.elapsedRealtime() }
    var selected by remember(sessionId) { mutableStateOf<Int?>(null) }
    var changes by remember(sessionId) { mutableIntStateOf(0) }
    var firstAnswerMs by remember(sessionId) { mutableStateOf<Long?>(null) }
    var submitted by remember(sessionId) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val textColor = colors.onSurface.toArgb()
    val hintColor = colors.onSurfaceVariant.toArgb()

    fun record(kind: String, summary: String, detail: JSONObject = JSONObject()) {
        val active = SentryRuntime.state.value.activeSession
        if (active == null || active.id != sessionId || active.mode != "LAB") return
        detail.put("questionId", "Q1").put("dwellMs", (SystemClock.elapsedRealtime() - enteredAt).coerceAtLeast(0))
        SentryRuntime.emit(kind, "NATIVE_LAB", summary, detail.toString(), isTest = true)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("原生答题实测", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(sessionId?.take(8)?.uppercase(Locale.ROOT) ?: "未开始", style = MaterialTheme.typography.labelMedium, color = colors.primary)
        }
        Text("Q1 · 下列哪个数是偶数？", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
        listOf("A. 3", "B. 4", "C. 7").forEachIndexed { index, option ->
            val chosen = selected == index
            Row(
                Modifier.fillMaxWidth()
                    .background(if (chosen) colors.primaryContainer else colors.surface, RoundedCornerShape(8.dp))
                    .border(if (chosen) 1.dp else 0.5.dp, if (chosen) colors.primary else colors.outlineVariant, RoundedCornerShape(8.dp))
                    .selectable(selected = chosen, enabled = ready, role = Role.RadioButton, onClick = {
                        val elapsed = (SystemClock.elapsedRealtime() - enteredAt).coerceAtLeast(0)
                        val changed = selected != null && selected != index
                        if (changed) changes++
                        if (firstAnswerMs == null) firstAnswerMs = elapsed
                        selected = index
                        submitted = false
                        record("ANSWER_PICK", "模拟题发生答案选择", JSONObject().put("isChange", changed).put("changes", changes).put("firstAnswerMs", firstAnswerMs))
                    }).padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                RadioButton(selected = chosen, onClick = null, enabled = ready)
                Spacer(Modifier.width(8.dp))
                Text(option, Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("改答 $changes 次", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Text(firstAnswerMs?.let { "首答 ${String.format(Locale.ROOT, "%.1f", it / 1_000.0)} 秒" } ?: "尚未作答", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        Button(enabled = ready && selected != null && !submitted, onClick = {
            submitted = true
            record("ANSWER_SUBMIT", "模拟题已提交", JSONObject().put("changes", changes).put("firstAnswerMs", firstAnswerMs))
        }, modifier = Modifier.height(44.dp)) {
            Icon(Icons.Outlined.Check, null, Modifier.width(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(if (submitted) "已提交" else "提交模拟答案")
        }

        HorizontalDivider(Modifier.padding(top = 4.dp), thickness = 0.5.dp, color = colors.outlineVariant)
        Text("文本操作", style = MaterialTheme.typography.titleSmall)
        key(sessionId) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(124.dp).border(0.5.dp, colors.outlineVariant, RoundedCornerShape(8.dp)),
                factory = { context ->
                    ObservedEditText(context).apply {
                        setText("这是一段用于复制、选择和粘贴的合成文本。")
                        hint = "在这里输入或粘贴测试文本"
                        textSize = 15f
                        val inset = (12 * resources.displayMetrics.density).toInt()
                        setPadding(inset, inset, inset, inset)
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        isSaveEnabled = false
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                        background = null
                    }
                },
                update = { edit ->
                    edit.isEnabled = ready
                    edit.setTextColor(textColor)
                    edit.setHintTextColor(hintColor)
                    edit.onObservation = { kind, summary, detail -> record(kind, summary, detail) }
                }
            )
        }
        Text("会话水印 · ${sessionId?.take(12) ?: "—"}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text("当前 App 画面保护", style = MaterialTheme.typography.titleSmall)
                Text(if (protection) "系统防截屏与覆盖层保护已请求" else "保护关闭，可实测截图与录屏回调", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Switch(checked = protection, enabled = ready, onCheckedChange = { if (ready) onProtection(it) })
        }
    }
}

private class ObservedEditText(context: Context) : EditText(context) {
    var onObservation: ((String, String, JSONObject) -> Unit)? = null
    private var lastSelectionLength = -1
    private var selectionUpdate: Runnable? = null

    init {
        setOnLongClickListener {
            onObservation?.invoke("LONG_PRESS", "原生文本区域发生长按", JSONObject().put("charLength", (selectionEnd - selectionStart).coerceAtLeast(0)))
            false
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        val oldLength = text?.length ?: 0
        val selectedLength = (selectionEnd - selectionStart).coerceAtLeast(0)
        val success = super.onTextContextMenuItem(id)
        if (!success) return false
        when (id) {
            android.R.id.paste, android.R.id.pasteAsPlainText -> {
                val insertedLength = ((text?.length ?: 0) - oldLength + selectedLength).coerceAtLeast(0)
                onObservation?.invoke("PASTE", "原生文本区域发生粘贴", JSONObject().put("charLength", insertedLength).put("replacedCharLength", selectedLength))
            }
            android.R.id.copy -> onObservation?.invoke("COPY", "原生文本区域发生复制", JSONObject().put("charLength", selectedLength))
            android.R.id.cut -> onObservation?.invoke("CUT", "原生文本区域发生剪切", JSONObject().put("charLength", selectedLength))
        }
        return true
    }

    override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        selectionUpdate?.let { removeCallbacks(it) }
        val length = (end - start).coerceAtLeast(0)
        selectionUpdate = Runnable {
            if (length > 0 && length != lastSelectionLength) onObservation?.invoke("TEXT_SELECTION", "原生文本区域发生文本选择", JSONObject().put("charLength", length))
            lastSelectionLength = length
        }.also { postDelayed(it, 250) }
    }

    override fun onDetachedFromWindow() {
        selectionUpdate?.let { removeCallbacks(it) }
        onObservation = null
        super.onDetachedFromWindow()
    }
}
