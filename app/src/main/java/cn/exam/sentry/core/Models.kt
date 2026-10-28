package cn.exam.sentry.core

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

data class SessionConfig(
    val label: String = "检测会话",
    val mode: String = "MONITOR",
    val targetPackage: String = "",
    val allowedPackages: Set<String> = emptySet(),
    val onlineCheck: Boolean = false,
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val label: String,
    val mode: String,
    val targetPackage: String,
    val allowedPackagesJson: String,
    val onlineCheck: Boolean,
    val startedWall: Long,
    val startedElapsed: Long,
    val lastHeartbeatWall: Long,
    val lastHeartbeatElapsed: Long,
    val endedWall: Long? = null,
    val status: String = "ACTIVE",
)

@Entity(tableName = "events", indices = [Index(value = ["sessionId", "seq"], unique = true)])
data class EventEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val seq: Long,
    val wallTime: Long,
    val elapsedTime: Long,
    val kind: String,
    val source: String,
    val summary: String,
    val detail: String,
    val severity: Int,
    val isTest: Boolean,
    val previousHash: String,
    val hash: String,
)

@Entity(tableName = "incidents", indices = [Index("sessionId")])
data class IncidentEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val ruleId: String,
    val title: String,
    val explanation: String,
    val severity: Int,
    val eventIdsJson: String,
    val wallTime: Long,
)

@Entity(tableName = "probes")
data class ProbeEntity(
    @PrimaryKey val id: String,
    val label: String,
    val status: String,
    val detail: String,
    val updatedWall: Long,
)

@Entity(tableName = "coverage", primaryKeys = ["sessionId", "phase"])
internal data class CoverageEntity(val sessionId: String, val phase: String, val capturedWall: Long, val probesJson: String)

data class SentryState(
    val activeSession: SessionEntity? = null,
    val sessions: List<SessionEntity> = emptyList(),
    val events: List<EventEntity> = emptyList(),
    val incidents: List<IncidentEntity> = emptyList(),
    val probes: List<ProbeEntity> = emptyList(),
)
