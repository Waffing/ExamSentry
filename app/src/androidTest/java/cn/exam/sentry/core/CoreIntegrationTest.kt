package cn.exam.sentry.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Generated markers always live in LAB sessions and explicitly identify their instrumentation source. */
@RunWith(AndroidJUnit4::class)
class CoreIntegrationTest {
    @Test fun roomQueueSignedExportsAndSessionPartition() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SentryRuntime.init(context)
        val previous = SentryRuntime.state.value.activeSession
        SentryRuntime.stop()
        if (previous != null) SentryRuntime.eventsFor(previous.id)
        val first = SentryRuntime.start(SessionConfig(label = "自动化验证会话（框架标记）", mode = "LAB"))
        try {
            repeat(16) { index ->
                SentryRuntime.emit("INSTRUMENTATION_MARKER", "INSTRUMENTATION", "测试框架生成的标记 $index", JSONObject().put("index", index).toString(), isTest = true)
            }
            SentryRuntime.heartbeat()
            val events = SentryRuntime.eventsFor(first.id)
            assertEquals((0 until 16).toList(), events.filter { it.kind == "INSTRUMENTATION_MARKER" }.map { JSONObject(it.detail).getInt("index") })
            assertTrue(events.all { it.sessionId == first.id && it.isTest })
            assertTrue(EvidenceHash.verify(events))
            assertEquals((1L..events.size.toLong()).toList(), events.map { it.seq })

            SentryRuntime.stop()
            SentryRuntime.eventsFor(first.id)
            val json = SentryRuntime.export(context, first.id, "JSON")
            val archive = JSONObject(json.readText())
            assertEquals("ENDED", archive.getJSONObject("payload").getJSONObject("session").getString("status"))
            assertTrue(archive.getJSONObject("payload").getJSONArray("coverage").length() >= 2)
            assertTrue(SentryRuntime.verify(json).startsWith("内容摘要、事件链与数字签名有效。"))

            archive.getJSONObject("payload").getJSONArray("events").getJSONObject(0).put("summary", "modified by test")
            val tampered = File(context.cacheDir, "tampered-core-test.json").apply { writeText(archive.toString()) }
            assertTrue(SentryRuntime.verify(tampered).startsWith("核验失败："))

            val pdf = SentryRuntime.export(context, first.id, "PDF")
            assertTrue(pdf.length() > 1000)
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertTrue(renderer.pageCount > 0)
                    renderer.openPage(0).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertTrue(hasNonWhitePixels(bitmap))
                        } finally { bitmap.recycle() }
                    }
                }
            }
            assertTrue(File(pdf.parentFile, "${pdf.nameWithoutExtension}.json").isFile)

            val png = SentryRuntime.export(context, first.id, "PNG")
            val bitmap = BitmapFactory.decodeFile(png.absolutePath)
            assertTrue(bitmap != null)
            if (bitmap != null) try {
                assertEquals(1080, bitmap.width)
                assertEquals(1520, bitmap.height)
                assertNotEquals(bitmap.getPixel(20, 20), bitmap.getPixel(20, 700))
            } finally { bitmap.recycle() }

            val second = SentryRuntime.start(SessionConfig(label = "会话隔离验证（框架标记）", mode = "LAB"))
            SentryRuntime.emit("INSTRUMENTATION_MARKER", "INSTRUMENTATION", "第二会话框架标记", isTest = true)
            val secondEvents = SentryRuntime.eventsFor(second.id)
            assertFalse(secondEvents.any { it.sessionId == first.id })
            assertTrue(EvidenceHash.verify(secondEvents))
            assertEquals(1, secondEvents.count { it.kind == "INSTRUMENTATION_MARKER" })
            assertTrue(SentryRuntime.eventsFor(first.id).size > secondEvents.size)
        } finally {
            SentryRuntime.stop()
            SentryRuntime.eventsFor(SentryRuntime.state.value.activeSession?.id ?: first.id)
        }
    }

    private fun hasNonWhitePixels(bitmap: Bitmap): Boolean {
        for (y in 30 until bitmap.height - 20 step 3) {
            for (x in 30 until bitmap.width - 20 step 3) {
                val value = bitmap.getPixel(x, y)
                if (Color.alpha(value) > 128 && (Color.red(value) < 220 || Color.green(value) < 220 || Color.blue(value) < 220)) return true
            }
        }
        return false
    }
}
