package app.readflow.ingest

import app.readflow.core.Box
import app.readflow.core.Transform
import org.junit.Assert.*
import org.junit.Test

class OcrRefinementTest {
    private fun word(text: String, left: Float = 20f, top: Float = 20f, confidence: Float? = .7f,
        width: Float = 120f, height: Float = 24f) = OcrWord(text, Box(left, top, left + width, top + height), confidence)
    private fun line(vararg words: OcrWord, angle: Float = 0f, block: Int = 0, index: Int = 0) =
        OcrLine(block, index, words.toList(), angle)
    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals(expected.first, actual.first, .005f)
        assertEquals(expected.second, actual.second, .005f)
    }

    @Test fun originalPhotoDimensionsAreBoundedWithoutUpscaling() {
        assertEquals(1f, OcrRefinement.fitScale(1280, 354), 0f)
        val scale = OcrRefinement.fitScale(16320, 12240)
        assertTrue(16320.0 * scale * 12240 * scale <= OcrRefinement.MAX_SOURCE_PIXELS + 2)
    }

    @Test fun arbitraryRotationIncludesAllCornersAndHasAnExactInverse() {
        for (degrees in listOf(-7f, 6f, 90f, 180f, 270f)) {
            val plan = OcrRefinement.rotatedCanvas(1280, 354, degrees)
            val inverse = plan.sourceToCanvas.inverse()
            for (point in listOf(0f to 0f, 1280f to 0f, 0f to 354f, 1280f to 354f, 450f to 190f)) {
                val mapped = plan.sourceToCanvas.map(point.first, point.second)
                assertTrue(mapped.first >= -.01f && mapped.first <= plan.width + .01f)
                assertTrue(mapped.second >= -.01f && mapped.second <= plan.height + .01f)
                assertPoint(point, inverse.map(mapped.first, mapped.second))
            }
        }
    }

    @Test fun compoundQuarterTurnDeskewCropAndPageScaleKeepHighlightCoordinates() {
        val quarter = OcrRefinement.rotatedCanvas(1400, 2000, 90f)
        val skew = OcrRefinement.rotatedCanvas(quarter.width, quarter.height, -5f)
        val crop = Transform(2f, 0f, 0f, 2f, -100f, -140f)
        val toCrop = OcrRefinement.compose(crop, OcrRefinement.compose(skew.sourceToCanvas, quarter.sourceToCanvas))
        val pageScale = Transform(2.35f, 0f, 0f, 2.5f)
        val cropToPage = OcrRefinement.compose(pageScale, toCrop.inverse())
        val sourcePoint = 400f to 700f
        val recognizedPoint = toCrop.map(sourcePoint.first, sourcePoint.second)
        assertPoint(pageScale.map(sourcePoint.first, sourcePoint.second), cropToPage.map(recognizedPoint.first, recognizedPoint.second))
    }

    @Test fun mappingCornersAvoidsInflatingWordBoxesTwice() {
        val original = word("Milanković’s")
        val rotation = OcrRefinement.rotatedCanvas(1000, 700, 8f).sourceToCanvas
        val restored = original.mapped(rotation).mapped(rotation.inverse())
        assertEquals(original.box.left, restored.box.left, .005f)
        assertEquals(original.box.bottom, restored.box.bottom, .005f)
    }

    @Test fun correctedLargePageAndCropsRemainWithinPixelBudgets() {
        val page = OcrRefinement.rotatedCanvas(3000, 3333, 12f)
        assertTrue(page.width.toLong() * page.height <= OcrRefinement.MAX_ROTATED_PIXELS)
        val target = line(word("Tiny", height = 8f, width = 8000f))
        val crop = OcrRefinement.cropBounds(target, 10000, 1000)
        val scale = OcrRefinement.cropScale(target, crop)
        val rotatedCrop = OcrRefinement.rotatedCanvas((crop.width * scale).toInt(), (crop.height * scale).toInt(), -9f,
            OcrRefinement.MAX_CROP_PIXELS)
        assertTrue(rotatedCrop.width.toLong() * rotatedCrop.height <= OcrRefinement.MAX_CROP_PIXELS)
    }

    @Test fun deskewRequiresSeveralAgreeingLinesAndIgnoresSmallNoise() {
        fun lines(angles: List<Float>) = angles.mapIndexed { i, angle -> line(word("Readable sentence", top = i * 40f), angle = angle) }
        assertEquals(-4.1f, OcrRefinement.deskewAngle(lines(listOf(4f, 4.1f, 4.2f)))!!, .001f)
        assertNull(OcrRefinement.deskewAngle(lines(listOf(4f, 4.1f))))
        assertNull(OcrRefinement.deskewAngle(lines(listOf(-4f, 1f, 6f))))
        assertNull(OcrRefinement.deskewAngle(lines(listOf(.1f, .2f, .3f))))
        assertNull(OcrRefinement.deskewAngle(lines(listOf(40f, 40f, 40f))))
    }

    @Test fun confidenceUnknownIsNotConfusedWithLowConfidence() {
        assertEquals(0f, OcrRefinement.confidence(0f)!!, 0f)
        assertTrue(OcrRefinement.retryPriority(line(word("Unreadable", confidence = OcrRefinement.confidence(0f)))) > 0f)
        assertNull(OcrRefinement.confidence(Float.NaN))
        assertNull(OcrRefinement.confidence(-1f))
        assertNull(OcrRefinement.confidence(1.2f))
        assertEquals(.5f, OcrRefinement.confidence(.5f)!!, 0f)
        assertTrue(OcrRefinement.retryIndices(listOf(line(word("Readable", confidence = null)))).isEmpty())
    }

    @Test fun retriesTargetWeakSmallOrSuspiciousTextAndHaveABoundedCount() {
        val clean = line(word("Milanković’s", confidence = .97f))
        assertEquals(0f, OcrRefinement.retryPriority(clean), 0f)
        assertTrue(OcrRefinement.retryPriority(line(word("Small", confidence = .97f, height = 12f))) > 0)
        assertTrue(OcrRefinement.retryPriority(line(word("|", confidence = null))) > 0)
        val lines = List(30) { line(word("Uncertain", confidence = .5f)) } + clean
        assertEquals(OcrRefinement.MAX_LINE_RETRIES, OcrRefinement.retryIndices(lines).size)
        assertFalse(OcrRefinement.retryIndices(lines).contains(30))
    }

    @Test fun cropPaddingClipsAtPhotoEdgesAndRetainsDiacriticRoom() {
        val crop = OcrRefinement.cropBounds(line(word("Köppen", left = 0f, top = 0f)), 130, 50)
        assertEquals(0f, crop.left, 0f)
        assertEquals(0f, crop.top, 0f)
        assertTrue(crop.bottom > 24f)
        assertTrue(crop.right <= 130f)
    }

    @Test fun strongerRecognitionOfTheSameNameCanReplaceTheLine() {
        val old = line(word("Milankovic’s", confidence = .7f))
        val next = line(word("Milanković’s", confidence = .95f))
        assertTrue(OcrRefinement.betterLine(old, next))
        assertEquals("Milankovic’s", old.words.single().text)
    }

    @Test fun smallScoreFluctuationOrUnknownScoreDoesNotRewriteText() {
        assertFalse(OcrRefinement.betterLine(line(word("Koppen", confidence = .85f)), line(word("Köppen", confidence = .89f))))
        assertFalse(OcrRefinement.betterLine(line(word("Koppen", confidence = null)), line(word("Köppen", confidence = .99f))))
        assertFalse(OcrRefinement.betterLine(line(word("Koppen")), line(word("Köppen", confidence = null))))
    }

    @Test fun neighboringLineOrMissingHardWordCannotWinOnConfidence() {
        val original = line(word("Milankovic’s", left = 20f), word("theory", left = 160f, confidence = .96f))
        assertFalse(OcrRefinement.betterLine(original, line(word("theory", left = 160f, confidence = .99f))))
        assertFalse(OcrRefinement.betterLine(original, line(word("Milanković’s", left = 20f, top = 65f, confidence = .99f),
            word("theory", left = 160f, top = 65f, confidence = .99f))))
    }

    @Test fun retryCannotTradeAWorseConfidentWordForABetterAverage() {
        val original = line(word("Milankovic’s", left = 20f, confidence = .5f), word("theory", left = 160f, confidence = .98f))
        assertFalse(OcrRefinement.betterLine(original, line(word("Milanković’s", left = 20f, confidence = .99f),
            word("therapy", left = 160f, confidence = .99f))))
    }

    @Test fun unknownConfidenceRequiresRemovingAnActualSuspiciousCharacter() {
        assertTrue(OcrRefinement.betterLine(line(word("K|ppen", confidence = null)), line(word("Köppen", confidence = .96f))))
    }

    @Test fun deskewCannotSilentlyLoseWordsOrChangeConfidentNames() {
        val old = listOf(line(word("Milanković’s", confidence = .98f), word("theory", left = 170f, confidence = .7f)))
        assertFalse(OcrRefinement.keepDeskewed(old, listOf(line(word("theory", left = 170f, confidence = .99f)))))
        assertFalse(OcrRefinement.keepDeskewed(old, listOf(line(word("Milankovic’s", confidence = .99f), word("theory", left = 170f, confidence = .99f)))))
        assertTrue(OcrRefinement.keepDeskewed(old, old))
    }
}
