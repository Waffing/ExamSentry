package cn.exam.sentry.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object EvidenceHash {
    const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun hash(event: EventEntity): String {
        val fields = listOf(
            event.id, event.sessionId, event.seq.toString(), event.wallTime.toString(), event.elapsedTime.toString(),
            event.kind, event.source, event.summary, event.detail, event.severity.toString(), event.isTest.toString(), event.previousHash,
        )
        val encoded = fields.joinToString("") { "${it.toByteArray(StandardCharsets.UTF_8).size}:$it" }
        return sha256(encoded.toByteArray(StandardCharsets.UTF_8))
    }

    fun verify(events: List<EventEntity>): Boolean {
        var previous = GENESIS
        events.forEachIndexed { index, event ->
            if (event.seq != index.toLong() + 1 || event.previousHash != previous || event.hash != hash(event)) return false
            if (index > 0 && event.sessionId != events.first().sessionId) return false
            previous = event.hash
        }
        return true
    }
}
