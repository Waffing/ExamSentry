package cn.exam.sentry.core

internal data class CollectedSignal(val kind: String, val summary: String, val detail: Map<String, Any> = emptyMap(), val severity: Int = 0)

/** Waiting for the selected exam app avoids flagging the setup screen as an exam departure. */
internal class ForegroundTracker(
    private val target: String,
    private val allowed: Set<String>,
    private val transient: Set<String>,
) {
    private var armed = false
    private var awayAt: Long? = null
    private var warned = false
    private val path = mutableListOf<String>()
    private var lastPackage: String? = null
    private var screenLocked = false
    private var approvedFlow = false

    fun approvedSystemFlow(active: Boolean): List<CollectedSignal> {
        approvedFlow = active
        if (!active) return emptyList()
        val hadDeparture = awayAt != null
        awayAt = null
        warned = false
        path.clear()
        lastPackage = null
        return if (hadDeparture) listOf(CollectedSignal("APP_DEPARTURE_SUSPENDED", "进入已批准的系统授权流程，暂停离开计时")) else emptyList()
    }

    fun screenLocked(locked: Boolean): List<CollectedSignal> {
        screenLocked = locked
        if (!locked) return emptyList()
        val hadDeparture = awayAt != null
        armed = false
        awayAt = null
        warned = false
        path.clear()
        lastPackage = null
        return if (hadDeparture) listOf(CollectedSignal("APP_DEPARTURE_SUSPENDED", "屏幕锁定，结束离开计时；锁屏区间不推断应用行为")) else emptyList()
    }

    fun background(elapsed: Long): List<CollectedSignal> {
        if (screenLocked || approvedFlow || !armed || awayAt != null) return emptyList()
        awayAt = elapsed
        warned = false
        lastPackage = null
        return listOf(CollectedSignal("APP_LEAVE", "考试画面进入后台", mapOf("targetPackage" to target, "destinationKnown" to false)))
    }

    fun observe(packageName: String, elapsed: Long): List<CollectedSignal> {
        if (screenLocked || approvedFlow || packageName.isBlank() || packageName in transient) return emptyList()
        if (packageName == target) {
            val wasArmed = armed
            armed = true
            lastPackage = packageName
            val start = awayAt
            awayAt = null
            warned = false
            if (start != null) {
                val result = CollectedSignal("APP_RETURN", "返回指定考试应用", mapOf("targetPackage" to target, "durationMs" to (elapsed - start).coerceAtLeast(0), "packages" to path.toList()))
                path.clear()
                return listOf(result)
            }
            return if (!wasArmed) listOf(CollectedSignal("TARGET_ENTERED", "已进入指定考试应用", mapOf("packageName" to target))) else emptyList()
        }
        if (!armed || packageName == lastPackage) return emptyList()
        lastPackage = packageName
        if (packageName in allowed) {
            if (awayAt != null) {
                val duration = (elapsed - (awayAt ?: elapsed)).coerceAtLeast(0)
                awayAt = null
                warned = false
                val previous = path.toList()
                path.clear()
                return listOf(CollectedSignal("ALLOWED_APP_ENTERED", "进入已允许应用", mapOf("packageName" to packageName, "durationMs" to duration, "packages" to previous)))
            }
            return listOf(CollectedSignal("ALLOWED_APP_ENTERED", "进入已允许应用", mapOf("packageName" to packageName)))
        }
        if (path.lastOrNull() != packageName && path.size < 30) path += packageName
        if (awayAt == null) {
            awayAt = elapsed
            warned = false
            return listOf(CollectedSignal("APP_LEAVE", "离开指定考试应用", mapOf("targetPackage" to target, "packageName" to packageName)))
        }
        return listOf(CollectedSignal("APP_SWITCH", "切换到非允许应用", mapOf("packageName" to packageName)))
    }

    fun tick(elapsed: Long): List<CollectedSignal> {
        val started = awayAt ?: return emptyList()
        if (warned || elapsed - started < 3_000) return emptyList()
        warned = true
        return listOf(CollectedSignal("APP_AWAY_THRESHOLD", "离开考试应用超过 3 秒", mapOf("durationMs" to (elapsed - started), "packages" to path.toList()), 1))
    }
}

internal data class RuleMatch(val key: String, val ruleId: String, val title: String, val explanation: String, val severity: Int, val eventIds: List<String>)

internal class BehaviorRules {
    companion object { const val VERSION = "1.0.0" }
    private val lastLeave = mutableMapOf<Boolean, EventEntity>()
    private val lastReturn = mutableMapOf<Boolean, EventEntity>()
    private val returns = mutableMapOf<Boolean, ArrayDeque<EventEntity>>()
    private val repeatedAt = mutableMapOf<Boolean, Long>()

    fun accept(event: EventEntity, durationMs: Long = 0, recordingVisible: Boolean = false): List<RuleMatch> {
        val test = event.isTest
        return when (event.kind) {
            "APP_LEAVE" -> { lastLeave[test] = event; lastReturn.remove(test); emptyList() }
            "APP_AWAY_THRESHOLD" -> {
                val leave = lastLeave[test] ?: return emptyList()
                listOf(RuleMatch("away:${leave.id}", "APP_AWAY", "离开考试应用", "考试应用离开检测范围达到 3 秒。目的应用是否已知，以关联事件为准；需结合现场情况复核。", 1, listOf(leave.id, event.id)))
            }
            "APP_RETURN" -> {
                val leave = lastLeave.remove(test) ?: return emptyList()
                lastReturn[test] = event
                val matches = mutableListOf<RuleMatch>()
                if (durationMs >= 3_000) matches += RuleMatch("away:${leave.id}", "APP_AWAY", "离开考试应用后返回", "离开考试应用 ${durationMs / 1000} 秒后返回。已知应用路径保存在关联事件中，目的应用未知时不推断其身份。", 1, listOf(leave.id, event.id))
                val recent = returns.getOrPut(test) { ArrayDeque() }
                recent.addLast(event)
                while (recent.isNotEmpty() && event.elapsedTime - recent.first().elapsedTime > 60_000) recent.removeFirst()
                if (recent.size >= 3 && event.elapsedTime - (repeatedAt[test] ?: Long.MIN_VALUE / 2) >= 60_000) {
                    repeatedAt[test] = event.elapsedTime
                    matches += RuleMatch("repeat:${event.id}", "REPEATED_SWITCH", "一分钟内反复离开、返回", "60 秒内已观察到至少 3 次离开、返回，建议重点复核。", 2, recent.takeLast(3).map { it.id })
                }
                matches
            }
            "ALLOWED_APP_ENTERED", "APP_DEPARTURE_SUSPENDED" -> { lastLeave.remove(test); lastReturn.remove(test); emptyList() }
            "PASTE" -> {
                val returned = lastReturn[test] ?: return emptyList()
                if (event.elapsedTime - returned.elapsedTime in 0..10_000) listOf(RuleMatch("return-paste:${returned.id}", "RETURN_THEN_PASTE", "返回后发生粘贴", "返回考试实测区后 10 秒内发生粘贴，已关联两项实际事件。粘贴内容未保存。", 1, listOf(returned.id, event.id))) else emptyList()
            }
            "FACE_ABSENT" -> if (durationMs >= 10_000) listOf(single(event, "FACE_ABSENT", "画面持续无人脸", "有效画面中连续至少 10 秒未检测到人脸。这是本地模型线索。")) else emptyList()
            "FACE_MULTIPLE" -> if (durationMs >= 3_000) listOf(single(event, "FACE_MULTIPLE", "画面持续出现多人脸", "画面中连续至少 3 秒检测到多个人脸，需人工核实。")) else emptyList()
            "SPEECH_ACTIVITY" -> if (durationMs >= 5_000) listOf(single(event, "SPEECH_ACTIVITY", "检测到持续语音活动", "本地模型检测到 10 秒窗口内累计至少 5 秒语音活动，不能确定说话者或内容。")) else emptyList()
            "SCREEN_CAPTURED" -> listOf(single(event, "SCREEN_CAPTURED", "系统通知本 App 发生截图", "系统截图回调在本 App 可见时触发，未采集截图图像。该接口不能覆盖所有截图方式。"))
            "SCREEN_RECORDING" -> if (recordingVisible) listOf(single(event, "SCREEN_RECORDING", "本 App 画面出现在录屏中", "Android 系统回调确认本 App 的可见画面正在录制；不推断其他应用的录屏状态。")) else emptyList()
            "WINDOW_OVERLAY", "WINDOW_MULTI_APP" -> listOf(single(event, "WINDOW_REVIEW", "观察到额外窗口线索", "已观察到额外窗口或多应用窗口。输入法、辅助工具和厂商窗口可能影响结果，请结合窗口元数据复核。"))
            "PERMISSION_REVOKED", "MONITOR_INTERRUPTED", "MONITORING_INTERRUPTED", "MEDIA_UNAVAILABLE", "PROBE_UNAVAILABLE" -> listOf(single(event, "MONITOR_HEALTH", "监测能力发生变化", event.summary))
            else -> emptyList()
        }
    }

    private fun single(event: EventEntity, rule: String, title: String, explanation: String) = RuleMatch("$rule:${event.id}", rule, title, explanation, event.severity.coerceAtLeast(1), listOf(event.id))
}
