package cn.exam.sentry.lab

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.exam.sentry.core.SentryRuntime
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.random.Random

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuizLab(sessionId: String?, ready: Boolean) {
    var subject by rememberSaveable(sessionId) { mutableStateOf("六科混合") }
    var seed by rememberSaveable(sessionId) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val paper = remember(subject, seed) {
        val random = Random(seed)
        if (subject == "六科混合") SchoolSubjects.flatMap { name ->
            SchoolQuestions.filter { it.subject == name }.shuffled(random).take(2)
        }.shuffled(random) else SchoolQuestions.filter { it.subject == subject }.shuffled(random)
    }
    var answers by rememberSaveable(sessionId, subject, seed) { mutableStateOf(IntArray(paper.size) { -1 }) }
    var changes by rememberSaveable(sessionId, subject, seed) { mutableStateOf(IntArray(paper.size)) }
    var firstAnswer by rememberSaveable(sessionId, subject, seed) { mutableStateOf(LongArray(paper.size) { -1 }) }
    var dwell by rememberSaveable(sessionId, subject, seed) { mutableStateOf(LongArray(paper.size)) }
    var index by rememberSaveable(sessionId, subject, seed) { mutableIntStateOf(0) }
    var submitted by rememberSaveable(sessionId, subject, seed) { mutableStateOf(false) }
    var started by rememberSaveable(sessionId, subject, seed) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var entered by rememberSaveable(sessionId, subject, seed) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var elapsed by rememberSaveable(sessionId, subject, seed) { mutableLongStateOf(0) }
    var recorded by rememberSaveable(sessionId, subject, seed) { mutableStateOf(false) }
    var showCard by rememberSaveable(sessionId) { mutableStateOf(false) }
    var confirmSubmit by remember { mutableStateOf(false) }
    var pendingSubject by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    val question = paper[index]
    val answered = answers.count { it >= 0 }
    val score = paper.indices.count { answers[it] == paper[it].correct }
    val paperId = "$subject-$seed"

    fun record(kind: String, summary: String, detail: JSONObject) {
        val active = SentryRuntime.state.value.activeSession
        if (!ready || active == null || active.id != sessionId || active.mode != "LAB") return
        SentryRuntime.emit(kind, "NATIVE_LAB", summary, detail.put("paperId", paperId).toString(), isTest = true)
    }
    fun questionDetail(): JSONObject = JSONObject().put("questionId", question.id).put("subject", question.subject)
        .put("dwellMs", dwell[index] + (SystemClock.elapsedRealtime() - entered).coerceAtLeast(0))
    fun navigate(next: Int) {
        if (next == index) return
        val now = SystemClock.elapsedRealtime()
        dwell = dwell.copyOf().also { it[index] += (now - entered).coerceAtLeast(0) }
        val previous = question.id
        index = next
        entered = now
        record("QUESTION_VIEW", "切换至第 ${next + 1} 题 · ${paper[next].subject}", JSONObject()
            .put("questionId", paper[next].id).put("subject", paper[next].subject).put("fromQuestionId", previous))
    }
    fun newPaper(name: String) { subject = name; seed++; showCard = false; pendingSubject = null }
    fun requestPaper(name: String) {
        if (answered > 0 && !submitted) pendingSubject = name else newPaper(name)
    }
    fun submit() {
        elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0)
        dwell = dwell.copyOf().also { it[index] += (SystemClock.elapsedRealtime() - entered).coerceAtLeast(0) }
        submitted = true
        confirmSubmit = false
        record("PAPER_SUBMIT", "六科练习交卷：$score / ${paper.size} 题正确", JSONObject()
            .put("questionCount", paper.size).put("answered", answered).put("correct", score)
            .put("answerChanges", changes.sum()).put("durationMs", elapsed))
    }

    LaunchedEffect(ready, subject, seed) {
        if (ready && !recorded) {
            started = SystemClock.elapsedRealtime()
            entered = started
            record("PAPER_STARTED", "已建立${subject}练习卷", JSONObject().put("questionIds", JSONArray(paper.map { it.id })))
            recorded = true
        }
        while (ready && !submitted) {
            elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0)
            delay(1000)
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("高中六科 · 答题实测", style = MaterialTheme.typography.titleMedium)
                Text("60 道离线原创练习 · 答题与操作同步记录", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Text("%02d:%02d".format(Locale.ROOT, elapsed / 60_000, elapsed / 1000 % 60), style = MaterialTheme.typography.titleMedium, color = colors.primary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("六科混合") + SchoolSubjects).forEach { name ->
                FilterChip(selected = subject == name, onClick = { if (subject != name) requestPaper(name) }, label = { Text(name) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        LinearProgressIndicator(progress = { answered.toFloat() / paper.size }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("已答 $answered / ${paper.size} · 改答 ${changes.sum()} 次", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Text(if (subject == "六科混合") "每科 2 题" else "本科 10 题", style = MaterialTheme.typography.labelMedium, color = colors.primary)
        }
        if (submitted) {
            Surface(color = colors.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("已交卷 · $score / ${paper.size} 题正确", style = MaterialTheme.typography.titleMedium)
                    Text("正确率 ${score * 100 / paper.size}% · 未答 ${paper.size - answered} 题。可用答题卡查看逐题解析。", style = MaterialTheme.typography.bodyMedium)
                    Text(SchoolSubjects.filter { name -> paper.any { it.subject == name } }.joinToString("  ·  ") { name ->
                        val items = paper.indices.filter { paper[it].subject == name }
                        "$name ${items.count { answers[it] == paper[it].correct }}/${items.size}"
                    }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("${index + 1}. ${question.subject} / ${question.topic}", style = MaterialTheme.typography.labelLarge, color = colors.primary)
        Text(question.prompt, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        question.options.forEachIndexed { option, text ->
            val chosen = answers[index] == option
            Row(Modifier.fillMaxWidth()
                .background(if (chosen) colors.primaryContainer else colors.surface, RoundedCornerShape(8.dp))
                .border(0.5.dp, if (chosen) colors.primary else colors.outlineVariant, RoundedCornerShape(8.dp))
                .selectable(selected = chosen, enabled = ready && !submitted, role = Role.RadioButton, onClick = {
                    if (answers[index] != option) {
                        val changed = answers[index] >= 0
                        if (changed) changes = changes.copyOf().also { it[index]++ }
                        if (firstAnswer[index] < 0) firstAnswer = firstAnswer.copyOf().also {
                            it[index] = dwell[index] + (SystemClock.elapsedRealtime() - entered).coerceAtLeast(0)
                        }
                        answers = answers.copyOf().also { it[index] = option }
                        record("ANSWER_PICK", "${question.subject}题发生${if (changed) "改答" else "首次选择"}", questionDetail()
                            .put("option", option).put("isChange", changed).put("changes", changes[index]).put("firstAnswerMs", firstAnswer[index]))
                    }
                }).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = chosen, onClick = null, enabled = ready && !submitted)
                Text("${'A' + option}. $text", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (submitted) {
            Text("${if (answers[index] == question.correct) "回答正确" else if (answers[index] < 0) "本题未答" else "本题答错"} · 正确答案 ${'A' + question.correct}", style = MaterialTheme.typography.titleSmall, color = colors.primary)
            Text(question.explanation, style = MaterialTheme.typography.bodyMedium)
        } else if (firstAnswer[index] >= 0) {
            Text("本题首答 %.1f 秒 · 改答 %d 次".format(Locale.ROOT, firstAnswer[index] / 1000.0, changes[index]), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { navigate(index - 1) }, enabled = index > 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("上一题") }
            OutlinedButton(onClick = { showCard = !showCard }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("答题卡") }
            OutlinedButton(onClick = { navigate(index + 1) }, enabled = index < paper.lastIndex, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("下一题") }
        }
        if (showCard) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                paper.indices.forEach { number ->
                    val marker = if (!submitted) { if (answers[number] >= 0) " · 已答" else "" }
                    else if (answers[number] == paper[number].correct) " · 对" else " · 错"
                    FilterChip(selected = number == index, onClick = { navigate(number) }, label = { Text("${number + 1}$marker") }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (answered < paper.size) confirmSubmit = true else submit() }, enabled = ready && !submitted, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (submitted) "已交卷" else "提交练习卷") }
            OutlinedButton(onClick = { requestPaper(subject) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新组卷") }
        }
        Text(if (ready) "记录首答、改答、切题与交卷；答得快或正确率高不会直接判定作弊。" else "开始实测会话后可作答，练习记录只保存在本机。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
    if (confirmSubmit) AlertDialog(onDismissRequest = { confirmSubmit = false }, title = { Text("仍有 ${paper.size - answered} 题未答") }, text = { Text("现在交卷会将未答题计为未得分，并显示答案解析。") }, confirmButton = { TextButton(onClick = { submit() }) { Text("交卷") } }, dismissButton = { TextButton(onClick = { confirmSubmit = false }) { Text("继续作答") } })
    pendingSubject?.let { next ->
        AlertDialog(onDismissRequest = { pendingSubject = null }, title = { Text("重新组卷？") }, text = { Text("当前答题进度将清空；已记录的操作事件仍保留在本次实测报告中。") }, confirmButton = { TextButton(onClick = { newPaper(next) }) { Text("重新组卷") } }, dismissButton = { TextButton(onClick = { pendingSubject = null }) { Text("取消") } })
    }
}
