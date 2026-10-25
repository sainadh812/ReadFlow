package app.readflow.ingest

import app.readflow.core.Box
import app.readflow.core.Transform
import org.junit.Assert.*
import org.junit.Test

class OcrQualityTest {
    private fun line(confidence: Float, index: Int = 0) = OcrLine(0, index, listOf(
        OcrWord("Financial statement", Box(20f, 40f * index, 220f, 40f * index + 24f), confidence)
    ), 0f)

    @Test fun missingOrUnknownSavedPresetKeepsExistingBalancedProcessing() {
        assertEquals(OcrQuality.BALANCED, OcrQuality.fromId(null))
        assertEquals(OcrQuality.BALANCED, OcrQuality.fromId("future-mode"))
        for (quality in OcrQuality.entries) assertEquals(quality, OcrQuality.fromId(quality.id))
        assertEquals(OcrRefinement.MAX_SOURCE_PIXELS, OcrQuality.BALANCED.maxSourcePixels)
        assertEquals(OcrRefinement.MAX_ROTATED_PIXELS, OcrQuality.BALANCED.maxRotatedPixels)
        assertEquals(OcrRefinement.MAX_LINE_RETRIES, OcrQuality.BALANCED.lineRetries)
    }

    @Test fun pdfAndRotationAllocationsRespectEveryPresetIncludingOversizedPages() {
        for (quality in OcrQuality.entries) {
            for ((width, height) in listOf(612 to 792, 20000 to 20000, 1 to 20000000)) {
                val page = OcrRefinement.pdfCanvas(width, height, quality)
                assertTrue("$quality ${width}x$height", page.width.toLong() * page.height <= quality.maxSourcePixels)
                assertTrue(page.width > 0 && page.height > 0)
                assertTrue(page.sourceToCanvas.a <= quality.pdfDpi / 72f)
                val rotated = OcrRefinement.rotatedCanvas(page.width, page.height, 12f, quality.maxRotatedPixels)
                assertTrue(rotated.width.toLong() * rotated.height <= quality.maxRotatedPixels)
            }
        }
        val fast = OcrRefinement.pdfCanvas(612, 792, OcrQuality.FAST)
        val balanced = OcrRefinement.pdfCanvas(612, 792, OcrQuality.BALANCED)
        assertTrue(fast.width < balanced.width)
        assertTrue(fast.height < balanced.height)
    }

    @Test fun highResolutionPhonePhotoFitsBudgetWithoutUpscalingSmallerImages() {
        for (quality in OcrQuality.entries) {
            val scale = OcrRefinement.fitScale(16320, 12240, quality.maxSourcePixels)
            val width = (16320 * scale).toInt()
            val height = (12240 * scale).toInt()
            assertTrue(width.toLong() * height <= quality.maxSourcePixels)
            assertEquals(1f, OcrRefinement.fitScale(1280, 354, quality.maxSourcePixels), 0f)
        }
    }

    @Test fun allPresetsKeepRecognizedCoordinatesOnTheSamePdfWordAfterRotationAndCrop() {
        val originalPoint = 131.5f to 391.25f
        for (quality in OcrQuality.entries) {
            val pdf = OcrRefinement.pdfCanvas(612, 792, quality)
            val rotation = OcrRefinement.rotatedCanvas(pdf.width, pdf.height, 90f, quality.maxRotatedPixels)
            val crop = Transform(1.75f, 0f, 0f, 1.75f, -100f, -150f)
            val pdfToCrop = OcrRefinement.compose(crop,
                OcrRefinement.compose(rotation.sourceToCanvas, pdf.sourceToCanvas))
            val inCrop = pdfToCrop.map(originalPoint.first, originalPoint.second)
            val restored = pdfToCrop.inverse().map(inCrop.first, inCrop.second)
            assertEquals(originalPoint.first, restored.first, .001f)
            assertEquals(originalPoint.second, restored.second, .001f)
        }
    }

    @Test fun fastDoesOnePassWhileHighChecksMoreUncertainLinesWithoutRetryingCleanText() {
        val lines = List(30) { line(.5f, it) } + line(.97f, 30)
        fun selected(quality: OcrQuality) = OcrRefinement.retryIndices(lines, quality.lineRetries, quality.retryConfidence)
        assertFalse(OcrQuality.FAST.automaticDeskew)
        assertTrue(selected(OcrQuality.FAST).isEmpty())
        assertEquals(6, selected(OcrQuality.BALANCED).size)
        assertEquals(12, selected(OcrQuality.HIGH).size)
        assertFalse(selected(OcrQuality.HIGH).contains(30))

        val moderatelyUncertain = listOf(line(.86f))
        assertTrue(OcrRefinement.retryIndices(moderatelyUncertain,
            OcrQuality.BALANCED.lineRetries, OcrQuality.BALANCED.retryConfidence).isEmpty())
        assertEquals(listOf(0), OcrRefinement.retryIndices(moderatelyUncertain,
            OcrQuality.HIGH.lineRetries, OcrQuality.HIGH.retryConfidence))
    }
}
