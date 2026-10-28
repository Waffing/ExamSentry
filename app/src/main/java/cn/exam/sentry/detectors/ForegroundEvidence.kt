package cn.exam.sentry.detectors

import android.os.SystemClock
import cn.exam.sentry.core.SentryRuntime

/** Orders independently delivered lifecycle, usage and accessibility observations. */
internal object ForegroundEvidence {
    private var sessionId = ""
    private var latestElapsed = 0L
    private var latestPackage = ""

    @Synchronized
    fun offer(packageName: String, source: String, observedElapsed: Long = SystemClock.elapsedRealtime(), observedWall: Long = System.currentTimeMillis()) {
        val active = SentryRuntime.state.value.activeSession ?: return
        if (sessionId != active.id) {
            sessionId = active.id
            latestElapsed = active.startedElapsed
            latestPackage = ""
        }
        if (packageName.isBlank() || observedElapsed < active.startedElapsed || observedElapsed < latestElapsed) return
        if (observedElapsed == latestElapsed && packageName == latestPackage) return
        latestElapsed = observedElapsed
        latestPackage = packageName
        SentryRuntime.foreground(packageName, source, observedElapsed, observedWall)
    }
}
