package cn.exam.sentry

import android.os.Bundle
import android.content.res.Configuration
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.exam.sentry.core.SentryRuntime
import cn.exam.sentry.detectors.ActivitySignals
import cn.exam.sentry.media.MediaMonitor
import cn.exam.sentry.ui.SentryApp

class MainActivity : ComponentActivity() {
    private lateinit var signals: ActivitySignals
    private lateinit var media: MediaMonitor
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SentryRuntime.init(applicationContext)
        enableEdgeToEdge()
        signals = ActivitySignals(this)
        media = MediaMonitor(this)
        setContent {
            val state by SentryRuntime.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.activeSession?.id) { if (state.activeSession != null) signals.refreshForSession() }
            SentryApp(this, media) { signals.setProtection(it) }
        }
    }
    override fun onStart() { super.onStart(); if (::signals.isInitialized) signals.onStart() }
    override fun onStop() {
        if (::media.isInitialized) media.stopAll()
        if (::signals.isInitialized) signals.onStop()
        super.onStop()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::signals.isInitialized) signals.onWindowFocusChanged(hasFocus)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::signals.isInitialized) signals.onConfigurationChanged(newConfig)
    }
    @Deprecated("Platform callback")
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        if (::signals.isInitialized) signals.onMultiWindowModeChanged(isInMultiWindowMode)
    }
    @Deprecated("Platform callback")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (::signals.isInitialized) signals.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::signals.isInitialized) signals.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
    override fun onDestroy() {
        if (::signals.isInitialized) signals.close()
        if (::media.isInitialized) media.stopAll()
        super.onDestroy()
    }
}
