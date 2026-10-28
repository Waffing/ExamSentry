package cn.exam.sentry.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceHashTest {
    @Test fun knownSha256Vector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", EvidenceHash.sha256("abc".toByteArray()))
    }

    @Test fun detectsChangedContentDeletionAndReordering() {
        val first = signed(base("first", 1, EvidenceHash.GENESIS))
        val second = signed(base("second", 2, first.hash))
        assertTrue(EvidenceHash.verify(listOf(first, second)))
        assertFalse(EvidenceHash.verify(listOf(first.copy(summary = "edited"), second)))
        assertFalse(EvidenceHash.verify(listOf(second)))
        assertFalse(EvidenceHash.verify(listOf(second, first)))
    }

    @Test fun cannotMixSessionsInOneChain() {
        val first = signed(base("first", 1, EvidenceHash.GENESIS))
        val second = signed(base("second", 2, first.hash).copy(sessionId = "another"))
        assertFalse(EvidenceHash.verify(listOf(first, second)))
    }

    @Test fun fieldBoundariesAndUnicodeAreUnambiguous() {
        val first = base("ab", 1, EvidenceHash.GENESIS).copy(sessionId = "c", summary = "中文记录")
        val second = first.copy(id = "a", sessionId = "bc")
        assertNotEquals(EvidenceHash.hash(first), EvidenceHash.hash(second))
        assertEquals(EvidenceHash.hash(first), EvidenceHash.hash(first.copy(hash = "not part of canonical data")))
    }

    private fun signed(event: EventEntity) = event.copy(hash = EvidenceHash.hash(event))
    private fun base(id: String, seq: Long, previous: String) = EventEntity(id, "session", seq, 1000, 100, "EVENT", "SYSTEM", "summary", "{}", 0, false, previous, "")
}
