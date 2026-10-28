package cn.exam.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorRulesTest {
    private fun tracker() = ForegroundTracker("exam", setOf("approved"), setOf("keyboard", "permission"))

    @Test fun setupDoesNotBecomeAnExamDeparture() {
        val tracker = tracker()
        assertTrue(tracker.observe("launcher", 100).isEmpty())
        assertTrue(tracker.tick(20_000).isEmpty())
        assertEquals("TARGET_ENTERED", tracker.observe("exam", 21_000).single().kind)
    }

    @Test fun actualTimestampsPreserveDwellAcrossDelayedBatch() {
        val tracker = tracker()
        tracker.observe("exam", 1_000)
        assertEquals("APP_LEAVE", tracker.observe("search", 2_000).single().kind)
        val returned = tracker.observe("exam", 14_000).single()
        assertEquals("APP_RETURN", returned.kind)
        assertEquals(12_000L, returned.detail["durationMs"])
        assertEquals(listOf("search"), returned.detail["packages"])
    }

    @Test fun transientWindowsDoNotEndOrStartDepartures() {
        val tracker = tracker()
        tracker.observe("exam", 0)
        assertTrue(tracker.observe("keyboard", 1_000).isEmpty())
        assertTrue(tracker.tick(4_000).isEmpty())
        tracker.observe("search", 5_000)
        assertTrue(tracker.observe("permission", 6_000).isEmpty())
        assertEquals("APP_AWAY_THRESHOLD", tracker.tick(8_000).single().kind)
        assertTrue(tracker.tick(9_000).isEmpty())
        assertEquals(5_000L, tracker.observe("exam", 10_000).single().detail["durationMs"])
    }

    @Test fun approvedAppIsExemptAndDoesNotCountAsReturnToExam() {
        val tracker = tracker()
        tracker.observe("exam", 0)
        assertEquals("ALLOWED_APP_ENTERED", tracker.observe("approved", 1_000).single().kind)
        assertTrue(tracker.tick(9_000).isEmpty())
        assertTrue(tracker.observe("exam", 10_000).isEmpty())
    }

    @Test fun approvedThenUnapprovedAppStartsNewDeparture() {
        val tracker = tracker()
        tracker.observe("exam", 0)
        tracker.background(1_000)
        assertEquals("ALLOWED_APP_ENTERED", tracker.observe("approved", 1_100).single().kind)
        assertTrue(tracker.tick(5_000).isEmpty())
        assertEquals("APP_LEAVE", tracker.observe("search", 6_000).single().kind)
        assertEquals("APP_AWAY_THRESHOLD", tracker.tick(9_000).single().kind)
        assertEquals(6_000L, tracker.observe("exam", 12_000).single().detail["durationMs"])
    }

    @Test fun lifecycleDeparturePreservesUnknownDestination() {
        val tracker = tracker()
        tracker.observe("exam", 0)
        val leave = tracker.background(1_000).single()
        assertEquals(false, leave.detail["destinationKnown"])
        assertFalse(leave.detail.containsKey("packageName"))
        assertEquals(4_000L, tracker.observe("exam", 5_000).single().detail["durationMs"])
    }

    @Test fun lockCancelsPendingLifecycleDepartureAndDoesNotInventReturn() {
        val tracker = tracker()
        tracker.observe("exam", 0)
        tracker.background(1_000)
        assertEquals("APP_DEPARTURE_SUSPENDED", tracker.screenLocked(true).single().kind)
        assertTrue(tracker.tick(20_000).isEmpty())
        assertTrue(tracker.background(21_000).isEmpty())
        assertTrue(tracker.observe("search", 22_000).isEmpty())
        tracker.screenLocked(false)
        assertEquals("TARGET_ENTERED", tracker.observe("exam", 30_000).single().kind)
    }

    @Test fun approvedSettingsFlowDoesNotCountDeparture() {
        val tracker = tracker()
        tracker.observe("exam", 0)
        tracker.approvedSystemFlow(true)
        assertTrue(tracker.background(1_000).isEmpty())
        assertTrue(tracker.observe("settings", 2_000).isEmpty())
        assertTrue(tracker.tick(20_000).isEmpty())
        tracker.approvedSystemFlow(false)
        assertTrue(tracker.observe("exam", 21_000).isEmpty())
    }

    @Test fun threeReturnsGenerateOneRollingWindowFinding() {
        val rules = BehaviorRules()
        var repeatCount = 0
        for (index in 0..3) {
            val base = index * 10_000L
            rules.accept(event("l$index", "APP_LEAVE", base))
            repeatCount += rules.accept(event("r$index", "APP_RETURN", base + 4_000), 4_000).count { it.ruleId == "REPEATED_SWITCH" }
        }
        assertEquals(1, repeatCount)
    }

    @Test fun rollingWindowExcludesOldReturns() {
        val rules = BehaviorRules()
        listOf(0L, 70_000L, 80_000L).forEachIndexed { i, base ->
            rules.accept(event("l$i", "APP_LEAVE", base))
            assertTrue(rules.accept(event("r$i", "APP_RETURN", base + 4_000), 4_000).none { it.ruleId == "REPEATED_SWITCH" })
        }
    }

    @Test fun testAndMonitoringEvidenceNeverJoin() {
        val rules = BehaviorRules()
        rules.accept(event("leave", "APP_LEAVE", 0, false))
        rules.accept(event("return", "APP_RETURN", 4_000, false), 4_000)
        assertTrue(rules.accept(event("testPaste", "PASTE", 5_000, true)).isEmpty())
        assertEquals("RETURN_THEN_PASTE", rules.accept(event("realPaste", "PASTE", 6_000, false)).single().ruleId)
    }

    @Test fun fastAnswerDoesNotMeanCheatingAndMediaRequiresSustainedEvidence() {
        val rules = BehaviorRules()
        assertTrue(rules.accept(event("fast", "ANSWER_PICK", 1_000)).isEmpty())
        assertTrue(rules.accept(event("face", "FACE_ABSENT", 2_000), 9_999).isEmpty())
        assertTrue(rules.accept(event("many", "FACE_MULTIPLE", 3_000), 2_999).isEmpty())
        assertTrue(rules.accept(event("speech", "SPEECH_ACTIVITY", 4_000), 4_999).isEmpty())
        assertEquals("FACE_ABSENT", rules.accept(event("face2", "FACE_ABSENT", 12_000), 10_000).single().ruleId)
    }

    @Test fun returnedPathUpdatesTheSameDepartureFinding() {
        val rules = BehaviorRules()
        rules.accept(event("leave", "APP_LEAVE", 0))
        val warning = rules.accept(event("warn", "APP_AWAY_THRESHOLD", 3_000), 3_000).single()
        val returned = rules.accept(event("back", "APP_RETURN", 12_000), 12_000).single()
        assertEquals(warning.key, returned.key)
        assertEquals(listOf("leave", "back"), returned.eventIds)
    }

    @Test fun recordingStopIsInformationalAndActiveRecordingIsReviewable() {
        val rules = BehaviorRules()
        val event = event("recording", "SCREEN_RECORDING", 1_000)
        assertTrue(rules.accept(event).isEmpty())
        assertEquals("SCREEN_RECORDING", rules.accept(event, recordingVisible = true).single().ruleId)
    }

    @Test fun serviceInterruptionBothNamesProduceHealthFinding() {
        val rules = BehaviorRules()
        assertEquals("MONITOR_HEALTH", rules.accept(event("a", "MONITOR_INTERRUPTED", 1_000)).single().ruleId)
        assertEquals("MONITOR_HEALTH", rules.accept(event("b", "MONITORING_INTERRUPTED", 2_000)).single().ruleId)
    }

    private fun event(id: String, kind: String, elapsed: Long, test: Boolean = false) = EventEntity(id, "session", 1, 1_000_000 + elapsed, elapsed, kind, "TEST", "summary", "{}", 0, test, EvidenceHash.GENESIS, "")
}
