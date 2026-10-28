package cn.exam.sentry.core

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase

@Dao
internal interface SentryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun session(value: SessionEntity)
    @Insert suspend fun event(value: EventEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun incident(value: IncidentEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun probe(value: ProbeEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun coverage(value: CoverageEntity)
    // Event insertion order survives both heartbeat updates and changes to the device clock.
    @Query("SELECT * FROM sessions ORDER BY (SELECT rowid FROM events WHERE sessionId = sessions.id ORDER BY seq ASC LIMIT 1) DESC, startedWall DESC") suspend fun sessions(): List<SessionEntity>
    @Query("DELETE FROM events WHERE sessionId IN (:ids)") suspend fun deleteEvents(ids: List<String>)
    @Query("DELETE FROM incidents WHERE sessionId IN (:ids)") suspend fun deleteIncidents(ids: List<String>)
    @Query("DELETE FROM coverage WHERE sessionId IN (:ids)") suspend fun deleteCoverage(ids: List<String>)
    @Query("DELETE FROM sessions WHERE id IN (:ids)") suspend fun deleteSessions(ids: List<String>)
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun sessionById(id: String): SessionEntity?
    @Query("SELECT * FROM sessions WHERE status = 'ACTIVE'") suspend fun unfinished(): List<SessionEntity>
    @Query("SELECT * FROM events WHERE sessionId = :id ORDER BY seq ASC") suspend fun events(id: String): List<EventEntity>
    @Query("SELECT * FROM events WHERE sessionId = :id ORDER BY seq DESC LIMIT 600") suspend fun recentEvents(id: String): List<EventEntity>
    @Query("SELECT * FROM events WHERE sessionId = :id ORDER BY seq DESC LIMIT 1") suspend fun lastEvent(id: String): EventEntity?
    @Query("SELECT * FROM incidents WHERE sessionId = :id ORDER BY wallTime DESC") suspend fun incidents(id: String): List<IncidentEntity>
    @Query("SELECT * FROM probes ORDER BY id ASC") suspend fun probes(): List<ProbeEntity>
    @Query("SELECT * FROM coverage WHERE sessionId = :id ORDER BY capturedWall ASC") suspend fun coverageFor(id: String): List<CoverageEntity>
}

@Database(entities = [SessionEntity::class, EventEntity::class, IncidentEntity::class, ProbeEntity::class, CoverageEntity::class], version = 1, exportSchema = false)
internal abstract class SentryDatabase : RoomDatabase() {
    abstract fun dao(): SentryDao
}
