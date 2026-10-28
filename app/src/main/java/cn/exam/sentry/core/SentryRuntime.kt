package cn.exam.sentry.core

import android.content.Context
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.room.Room
import androidx.room.withTransaction
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** All writes, including sequence allocation and session transitions, run through one command queue. */
object SentryRuntime {
    private val mutableState = MutableStateFlow(SentryState())
    val state: StateFlow<SentryState> = mutableState.asStateFlow()
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context
    private lateinit var database: SentryDatabase
    private lateinit var dao: SentryDao
    private var initialized = false
    private var current: SessionEntity? = null
    private var rules = BehaviorRules()
    private var foregroundTracker: ForegroundTracker? = null
    private var staleHeartbeat = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        database = Room.databaseBuilder(app, SentryDatabase::class.java, "exam-sentry.db").build()
        dao = database.dao()
        initialized = true
        commands.trySend { recover(); publish() }
        scope.launch {
            for (command in commands) {
                try { command() } catch (error: Exception) {
                    val problem = ProbeEntity("core_storage", "本地记录", "ERROR", "本地操作失败：${error.javaClass.simpleName}。请检查存储空间并重新打开应用。", System.currentTimeMillis())
                    mutableState.value = mutableState.value.copy(probes = mutableState.value.probes.filterNot { it.id == problem.id } + problem)
                }
            }
        }
        scope.launch {
            while (true) {
                delay(5_000)
                if (state.value.activeSession != null) enqueue { checkHeartbeatHealth() }
            }
        }
    }

    suspend fun start(config: SessionConfig): SessionEntity = serial {
        check(current == null) { "已有监测会话，请先结束当前会话。" }
        require(config.mode == "MONITOR" || config.mode == "LAB") { "会话模式无效。" }
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val target = config.targetPackage.ifBlank { app.packageName }
        val allowed = config.allowedPackages.filter { it.isNotBlank() }.toMutableSet()
        if (target != app.packageName) allowed += app.packageName
        val session = SessionEntity(
            UUID.randomUUID().toString(), config.label.trim().take(80).ifBlank { "检测会话" }, config.mode,
            target, JSONArray(allowed.sorted()).toString(), config.onlineCheck, wall, elapsed, wall, elapsed,
        )
        dao.session(session)
        current = session
        staleHeartbeat = false
        rules = BehaviorRules()
        val keyboards = (app.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.enabledInputMethodList.orEmpty().map { it.packageName }
        val transient = setOf("com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller") + keyboards
        foregroundTracker = ForegroundTracker(target, allowed, transient)
        coverage(session.id, "BASELINE")
        append(session, "SESSION_STARTED", "RUNTIME", "${if (config.mode == "LAB") "实测" else "监测"}会话开始", JSONObject().put("rulesVersion", BehaviorRules.VERSION).put("targetPackage", target).put("onlineCheck", config.onlineCheck).toString(), 0)
        if (target == app.packageName) processSignals(session, foregroundTracker?.observe(target, elapsed).orEmpty(), "ACTIVITY")
        publish()
        session
    }

    fun stop() = enqueue {
        val session = current ?: return@enqueue
        append(session, "SESSION_ENDED", "RUNTIME", "会话已由用户结束", "{}", 0)
        val ended = session.copy(status = "ENDED", endedWall = System.currentTimeMillis(), lastHeartbeatWall = System.currentTimeMillis(), lastHeartbeatElapsed = SystemClock.elapsedRealtime())
        dao.session(ended)
        coverage(session.id, "FINAL")
        current = null
        staleHeartbeat = false
        foregroundTracker = null
        publish()
    }

    fun heartbeat() = enqueue {
        val session = current ?: return@enqueue
        val elapsed = SystemClock.elapsedRealtime()
        processSignals(session, foregroundTracker?.tick(elapsed).orEmpty(), "RUNTIME_TIMER")
        if (staleHeartbeat) {
            append(session, "MONITORING_RESUMED", "HEALTH", "持续监测心跳恢复；缺口期间行为仍无法确认", JSONObject().put("lastHeartbeatWall", session.lastHeartbeatWall).put("gapMs", (elapsed - session.lastHeartbeatElapsed).coerceAtLeast(0)).toString(), 1)
            staleHeartbeat = false
        }
        if (elapsed - session.lastHeartbeatElapsed >= 5_000) {
            current = session.copy(lastHeartbeatWall = System.currentTimeMillis(), lastHeartbeatElapsed = elapsed)
            dao.session(current ?: session)
            publish()
        }
    }

    fun foreground(packageName: String, source: String, observedElapsed: Long = SystemClock.elapsedRealtime(), observedWall: Long = System.currentTimeMillis()) {
        val expected = state.value.activeSession?.id ?: return
        enqueue {
            val session = current?.takeIf { it.id == expected } ?: return@enqueue
            if (observedElapsed < session.startedElapsed || observedElapsed > SystemClock.elapsedRealtime() + 1_000) return@enqueue
            processSignals(session, foregroundTracker?.observe(packageName, observedElapsed).orEmpty(), source, observedElapsed, observedWall)
            publish()
        }
    }

    fun emit(kind: String, source: String, summary: String, detail: String = "{}", severity: Int = 0, isTest: Boolean? = null) {
        val expected = state.value.activeSession?.id ?: return
        val observedElapsed = SystemClock.elapsedRealtime()
        val observedWall = System.currentTimeMillis()
        enqueue {
            val session = current?.takeIf { it.id == expected } ?: return@enqueue
            val validDetail = try { JSONObject(detail).toString() } catch (_: Exception) { JSONObject().put("detailInvalid", true).toString() }
            append(session, kind.take(80), source.take(80), summary.take(800), validDetail, severity.coerceIn(0, 2), isTest, observedElapsed, observedWall)
            when (kind) {
                "SCREEN_LOCKED" -> processSignals(session, foregroundTracker?.screenLocked(true).orEmpty(), "SCREEN_STATE")
                "SCREEN_AVAILABLE" -> foregroundTracker?.screenLocked(false)
                "APPROVED_SYSTEM_FLOW_ENTER" -> processSignals(session, foregroundTracker?.approvedSystemFlow(true).orEmpty(), "APPROVED_SYSTEM_FLOW")
                "APPROVED_SYSTEM_FLOW_EXIT" -> foregroundTracker?.approvedSystemFlow(false)
            }
            if (session.targetPackage == app.packageName) {
                when (kind) {
                    "APP_BACKGROUND" -> processSignals(session, foregroundTracker?.background(observedElapsed).orEmpty(), "ACTIVITY_LIFECYCLE", observedElapsed, observedWall)
                    "APP_FOREGROUND" -> processSignals(session, foregroundTracker?.observe(app.packageName, observedElapsed).orEmpty(), "ACTIVITY_LIFECYCLE", observedElapsed, observedWall)
                }
            }
            publish()
        }
    }

    fun probe(id: String, label: String, status: String, detail: String) = enqueue {
        val old = dao.probes().firstOrNull { it.id == id }
        val wall = System.currentTimeMillis()
        if (old?.status == status && old.detail == detail && wall - old.updatedWall < 10_000) return@enqueue
        dao.probe(ProbeEntity(id, label, status, detail, wall))
        val session = current
        if (session != null && old?.status in setOf("ACTIVE", "AVAILABLE") && status in setOf("PERMISSION_REQUIRED", "ERROR", "STALE")) {
            append(session, "PROBE_UNAVAILABLE", "HEALTH", "$label：$detail", JSONObject().put("probeId", id).put("before", old?.status).put("after", status).toString(), 1)
        }
        publish()
    }

    suspend fun eventsFor(sessionId: String): List<EventEntity> = serial { dao.events(sessionId) }
    suspend fun incidentsFor(sessionId: String): List<IncidentEntity> = serial { dao.incidents(sessionId) }

    suspend fun export(context: Context, sessionId: String, format: String): File {
        val bundle = serial {
            val session = dao.sessionById(sessionId) ?: error("找不到此会话。")
            if (session.status == "ACTIVE") coverage(sessionId, "EXPORT_LIVE")
            ReportBundle(session, dao.events(sessionId), dao.incidents(sessionId), dao.coverageFor(sessionId))
        }
        return withContext(Dispatchers.IO) { ReportArchive.export(context.applicationContext, bundle, format) }
    }

    suspend fun verify(file: File): String = withContext(Dispatchers.IO) { ReportArchive.verify(file) }

    private suspend fun recover() {
        val previous = dao.unfinished()
        dao.probes().filter { it.status == "ACTIVE" || it.status == "AVAILABLE" }.forEach {
            dao.probe(it.copy(status = "STALE", detail = "上次运行的状态，等待本次重新检测。", updatedWall = System.currentTimeMillis()))
        }
        for (session in previous) {
            val wall = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            val detail = JSONObject().put("lastHeartbeatWall", session.lastHeartbeatWall).put("recoveredWall", wall)
                .put("lastHeartbeatElapsed", session.lastHeartbeatElapsed).put("recoveredElapsed", elapsed)
                .put("gapDurationIsEstimate", true).put("bootMayHaveChanged", elapsed < session.lastHeartbeatElapsed)
            rules = BehaviorRules()
            append(session, "MONITOR_INTERRUPTED", "RECOVERY", "发现上次会话异常结束；最后心跳至恢复之间的行为无法确认", detail.toString(), 1)
            dao.session(session.copy(status = "INTERRUPTED", endedWall = wall))
            coverage(session.id, "RECOVERY")
        }
        current = null
    }

    private suspend fun processSignals(session: SessionEntity, signals: List<CollectedSignal>, source: String, observedElapsed: Long = SystemClock.elapsedRealtime(), observedWall: Long = System.currentTimeMillis()) {
        signals.forEach { signal ->
            val detail = JSONObject()
            signal.detail.forEach { (key, value) -> detail.put(key, if (value is Collection<*>) JSONArray(value) else value) }
            append(session, signal.kind, source, signal.summary, detail.toString(), signal.severity, observedElapsed = observedElapsed, observedWall = observedWall)
        }
    }

    private suspend fun append(session: SessionEntity, kind: String, source: String, summary: String, detail: String, severity: Int, isTest: Boolean? = null, observedElapsed: Long = SystemClock.elapsedRealtime(), observedWall: Long = System.currentTimeMillis()) {
        database.withTransaction {
            val last = dao.lastEvent(session.id)
            val unsigned = EventEntity(
                UUID.randomUUID().toString(), session.id, (last?.seq ?: 0) + 1,
                observedWall, observedElapsed, kind, source, summary, detail, severity,
                isTest ?: (session.mode == "LAB"), last?.hash ?: EvidenceHash.GENESIS, "",
            )
            val event = unsigned.copy(hash = EvidenceHash.hash(unsigned))
            dao.event(event)
            val attributes = JSONObject(detail)
            val duration = when {
                attributes.has("durationMs") -> attributes.optLong("durationMs", 0)
                attributes.has("speechMs") -> attributes.optLong("speechMs", 0)
                attributes.has("durationSeconds") -> (attributes.optDouble("durationSeconds", 0.0) * 1000).toLong()
                else -> 0L
            }
            for (match in rules.accept(event, duration, attributes.optBoolean("visibleInRecording", false))) {
                dao.incident(IncidentEntity(
                    EvidenceHash.sha256("${session.id}:${match.key}".toByteArray()), session.id, match.ruleId,
                    match.title, match.explanation, match.severity, JSONArray(match.eventIds).toString(), event.wallTime,
                ))
            }
        }
    }

    private suspend fun coverage(sessionId: String, phase: String) {
        dao.coverage(CoverageEntity(sessionId, phase, System.currentTimeMillis(), JSONArray(dao.probes().map { it.asJson() }).toString()))
    }

    private suspend fun checkHeartbeatHealth() {
        val session = current ?: return
        val gap = SystemClock.elapsedRealtime() - session.lastHeartbeatElapsed
        if (staleHeartbeat || gap < 20_000) return
        staleHeartbeat = true
        dao.probe(ProbeEntity("service.monitor", "持续监测服务", "STALE", "超过 20 秒未收到监测心跳；会话仍保留，期间行为没有连续采集保证。", System.currentTimeMillis()))
        append(session, "MONITORING_INTERRUPTED", "HEALTH", "持续监测心跳已过期，请结束会话或重新检查服务", JSONObject().put("lastHeartbeatWall", session.lastHeartbeatWall).put("gapMs", gap).toString(), 2)
        publish()
    }

    private suspend fun publish() {
        val sessions = dao.sessions()
        val selected = current?.id ?: sessions.firstOrNull()?.id
        mutableState.value = SentryState(
            current, sessions,
            selected?.let { dao.recentEvents(it) }.orEmpty(),
            selected?.let { dao.incidents(it) }.orEmpty(),
            dao.probes(),
        )
    }

    private fun enqueue(block: suspend () -> Unit) {
        check(initialized) { "SentryRuntime.init(context) must be called first" }
        check(commands.trySend(block).isSuccess) { "记录队列不可用。" }
    }

    private suspend fun <T> serial(block: suspend () -> T): T {
        check(initialized) { "SentryRuntime.init(context) must be called first" }
        val result = CompletableDeferred<T>()
        commands.send {
            try { result.complete(block()) } catch (error: Exception) { result.completeExceptionally(error) }
        }
        return result.await()
    }
}
