package app.readflow

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readflow.ingest.LocalExtraction
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real ML Kit checks, separate from deterministic geometry/policy unit tests. */
@RunWith(AndroidJUnit4::class)
class OcrRefinementDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun image(width: Int, height: Int, skew: Float): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.rotate(skew)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f }
            listOf("Winter brings colder weather.", "Summer brings warmer weather.", "Orbit changes happen slowly.")
                .forEachIndexed { index, text -> canvas.drawText(text, 160f, 180f + index * 100f, paint) }
            File(context.cacheDir, "ocr-refinement-${width}-${height}-${skew}.png").apply {
                outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally { bitmap.recycle() }
    }

    @Test fun slightSkewPreservesUniqueWordsAndSourceCoordinates() = runBlocking {
        val file = image(1700, 700, 5f)
        try {
            val page = LocalExtraction().extract(file.path, 0)
            for (word in listOf("Winter", "Summer", "Orbit")) {
                assertEquals("No missing or duplicated $word after expanded-crop retries", 1,
                    page.elements.count { it.text.equals(word, ignoreCase = true) })
            }
            val summer = page.elements.single { it.text.equals("Summer", ignoreCase = true) }.boxes.single()
            // The first word's center was drawn at roughly (280, 257), then rotated 5 degrees.
            assertTrue("Highlight must map back to the sloping original", summer.contains(256f, 280f))
            assertEquals(1700f, page.width, 0f)
            assertEquals(700f, page.height, 0f)
        } finally { file.delete() }
    }

    @Test fun largePhotoKeepsOriginalDimensionsAfterBoundedDecode() = runBlocking {
        val file = image(3600, 3200, 0f)
        try {
            val page = LocalExtraction().extract(file.path, 0)
            assertEquals(3600f, page.width, 0f)
            assertEquals(3200f, page.height, 0f)
            val summer = page.elements.single { it.text.equals("Summer", ignoreCase = true) }.boxes.single()
            assertTrue("Highlight must be scaled back to source pixels", summer.contains(280f, 258f))
            assertTrue(page.elements.all { element -> element.boxes.all { box ->
                box.left >= 0f && box.top >= 0f && box.right <= page.width && box.bottom <= page.height
            } })
        } finally { file.delete() }
    }
}
