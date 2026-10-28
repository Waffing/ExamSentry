package cn.exam.sentry.lab

import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.exam.sentry.core.SentryRuntime
import org.json.JSONObject

@Composable
fun NativeExamLab(sessionId: String?, protection: Boolean, onProtection: (Boolean) -> Unit) {
    val runtime by SentryRuntime.state.collectAsState()
    val ready = runtime.activeSession?.let { it.id == sessionId && it.mode == "LAB" } == true
    val enteredAt = remember(sessionId) { SystemClock.elapsedRealtime() }
    val colors = MaterialTheme.colorScheme
    val textColor = colors.onSurface.toArgb()
    val hintColor = colors.onSurfaceVariant.toArgb()

    fun record(kind: String, summary: String, detail: JSONObject = JSONObject()) {
        val active = SentryRuntime.state.value.activeSession
        if (active == null || active.id != sessionId || active.mode != "LAB") return
        detail.put("area", "text-operations").put("dwellMs", (SystemClock.elapsedRealtime() - enteredAt).coerceAtLeast(0))
        SentryRuntime.emit(kind, "NATIVE_LAB", summary, detail.toString(), isTest = true)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        QuizLab(sessionId, ready)

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
