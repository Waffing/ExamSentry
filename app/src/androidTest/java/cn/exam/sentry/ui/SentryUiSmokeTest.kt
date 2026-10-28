package cn.exam.sentry.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import cn.exam.sentry.MainActivity
import cn.exam.sentry.core.SentryRuntime
import cn.exam.sentry.detectors.MonitoringService
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class SentryUiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun prepareLocalPreview() {
        SentryRuntime.stop()
        compose.waitUntil(10_000) { SentryRuntime.state.value.activeSession == null }
        context.stopService(Intent(context, MonitoringService::class.java))
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}").use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
        context.getSharedPreferences("sentry_settings", 0).edit().putInt("theme", 1).putBoolean("online", false).commit()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    @After
    fun finishLocalPreview() {
        SentryRuntime.stop()
        compose.waitUntil(10_000) { SentryRuntime.state.value.activeSession == null }
        context.stopService(Intent(context, MonitoringService::class.java))
    }

    @Test
    fun labActionsCreateTestEvidenceAndReportsStayReachable() {
        compose.onNodeWithText("考境哨兵").assertIsDisplayed()
        assertNavigationFits()
        saveScreenshot("overview")

        compose.onNodeWithText("实测", substring = false).performClick()
        compose.onNodeWithText("开始实测会话").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { SentryRuntime.state.value.activeSession?.mode == "LAB" }
        val sessionId = requireNotNull(SentryRuntime.state.value.activeSession).id
        compose.onNodeWithText("A. 3").performScrollTo().performClick()
        compose.onNodeWithText("提交模拟答案").performScrollTo().performClick()
        compose.onNodeWithText("已提交", substring = false).assertIsDisplayed()
        compose.waitUntil(10_000) {
            SentryRuntime.state.value.events.any { it.sessionId == sessionId && it.kind == "ANSWER_SUBMIT" }
        }
        val pick = SentryRuntime.state.value.events.first { it.sessionId == sessionId && it.kind == "ANSWER_PICK" }
        assertTrue(pick.isTest)
        assertFalse("Raw selected answer must not be stored", JSONObject(pick.detail).has("answer"))
        assertTrue("Dwell time must start before picking", JSONObject(pick.detail).getLong("firstAnswerMs") > 0)
        saveScreenshot("lab")

        compose.onNodeWithContentDescription("打开对照页").performScrollTo().assertIsDisplayed()
        compose.waitUntil(20_000) {
            SentryRuntime.state.value.events.any { it.sessionId == sessionId && it.kind == "BROWSER_READY" && it.isTest }
        }
        compose.onNodeWithContentDescription("打开对照页").performClick()
        compose.onNodeWithText("对照页 B").assertIsDisplayed()
        compose.waitUntil(20_000) {
            SentryRuntime.state.value.events.any { it.sessionId == sessionId && it.kind == "DUPLICATE_PAGE" && it.isTest }
        }
        saveScreenshot("browser-dual-page")

        compose.onNodeWithText("监测", substring = false).performClick()
        compose.onNodeWithText("持续监测", substring = false).performScrollTo().assertIsDisplayed()
        saveScreenshot("monitor")
        compose.onNodeWithText("结束会话").performScrollTo().performClick()
        compose.waitUntil(10_000) { SentryRuntime.state.value.activeSession == null }
        compose.onNodeWithText("报告", substring = false).performClick()
        compose.onNodeWithText("检测报告", substring = false).performScrollTo().assertIsDisplayed()
        for (format in listOf("JSON", "PDF", "PNG")) {
            compose.onAllNodesWithText(format, substring = false).onFirst().performScrollTo().assertIsDisplayed().assertIsEnabled()
        }
        saveScreenshot("report")
        assertNavigationFits()
    }

    @Test
    fun darkAppearanceAndNavigationRemainUsableAtDeviceWidth() {
        compose.onNodeWithText("考境哨兵").assertIsDisplayed()
        val light = saveScreenshot("overview-light")
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("深色", substring = false).performClick()
        compose.onNodeWithText("完成", substring = false).performClick()
        compose.waitForIdle()
        val dark = saveScreenshot("overview-dark")
        assertEquals(2, context.getSharedPreferences("sentry_settings", 0).getInt("theme", 0))
        val lightPixel = light.getPixel(4, light.height / 2)
        val darkPixel = dark.getPixel(4, dark.height / 2)
        assertTrue("App edge should use the light surface", Color.red(lightPixel) > 180)
        assertTrue("App edge should use the dark surface", Color.red(darkPixel) < 100)
        assertNavigationFits()
        compose.onNodeWithText("监测", substring = false).performClick()
        compose.onNodeWithText("开始监测", substring = false).performScrollTo().assertIsEnabled()
        compose.onNodeWithText("实测", substring = false).performClick()
        compose.onNodeWithText("开始实测会话").assertIsDisplayed()
        compose.onNodeWithText("报告", substring = false).performClick()
        compose.onNodeWithText("检测报告", substring = false).performScrollTo().assertIsDisplayed()
        assertNavigationFits()
        light.recycle()
        dark.recycle()
    }

    private fun assertNavigationFits() {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val bounds = listOf("总览", "监测", "实测", "报告").map { name ->
            val node = compose.onNodeWithText(name, substring = false).assertIsDisplayed().fetchSemanticsNode()
            node.boundsInRoot.also { rectangle ->
                assertTrue("$name should have positive width", rectangle.width > 0)
                assertTrue("$name should fit horizontal viewport", rectangle.left >= root.left && rectangle.right <= root.right)
                assertTrue("$name should fit vertical viewport", rectangle.top >= root.top && rectangle.bottom <= root.bottom)
            }
        }
        bounds.zipWithNext().forEach { (left: Rect, right: Rect) ->
            assertTrue("Navigation labels must not overlap", left.right <= right.left)
        }
    }

    private fun saveScreenshot(name: String): Bitmap {
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "System screenshot unavailable" }
        val directory = File(context.cacheDir, "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        return bitmap
    }
}
