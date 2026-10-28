package cn.exam.sentry.media

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ModelIntegrationTest {
    @Test(timeout = 60_000)
    fun bundledSileroExecutesFiniteStreamingSilence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SileroVad(context).use { model ->
            repeat(40) {
                val probability = model.probability(ShortArray(SileroVad.FRAME_SAMPLES))
                assertTrue("Actual model output must be finite", probability.isFinite())
                assertTrue("Actual probability must be in [0,1]", probability in 0f..1f)
            }
            model.reset()
            val probability = model.probability(ShortArray(SileroVad.FRAME_SAMPLES))
            assertTrue(probability.isFinite() && probability in 0f..1f)
        }
    }

    @Test(timeout = 60_000)
    fun bundledMlKitExecutesOnSyntheticBlankBitmap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MlKit.initialize(context)
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
        )
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.LTGRAY)
        try {
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 45, TimeUnit.SECONDS)
            assertEquals("A synthetic blank image has no expected face", 0, faces.size)
        } finally {
            detector.close()
            bitmap.recycle()
        }
    }
}
