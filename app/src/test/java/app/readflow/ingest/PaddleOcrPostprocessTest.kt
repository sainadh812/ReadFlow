package app.readflow.ingest

import app.readflow.core.Box
import app.readflow.core.TextElement
import org.junit.Assert.*
import org.junit.Test

class PaddleOcrPostprocessTest {
    private val region = PaddleQuad(listOf(PaddlePoint(0f, 0f), PaddlePoint(300f, 0f),
        PaddlePoint(300f, 60f), PaddlePoint(0f, 60f)), .95f)
    private fun reference(text: String, x: Float, y: Float = 20f) = TextElement(text, listOf(Box(x, y, x + 20f, y + 10f)))
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid model output must be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun dictionaryPreservesUnicodeDuplicateEntriesAndQuotedApostrophe() {
        val values = listOf("ć", "ö", "''''", "’", "'0'", "'" + '"' + "'") + List(830) { "a" }
        val dictionary = PaddleOcrPostprocess.dictionary("PostProcess:\n  character_dict:\n" + values.joinToString("\n") { "  - $it" })
        assertEquals(838, dictionary.size)
        assertEquals(listOf("", "ć", "ö", "'", "’", "0", "\""), dictionary.take(7))
        assertEquals(" ", dictionary.last())
        assertEquals(830, dictionary.count { it == "a" })
    }

    @Test fun malformedOrChangedDictionaryFailsInsteadOfShiftingAllCharacters() {
        rejected { PaddleOcrPostprocess.dictionary("  character_dict:\n  - a") }
        rejected { PaddleOcrPostprocess.dictionary("  character_dict:\n" + List(836) { "  - xy" }.joinToString("\n")) }
        rejected { PaddleOcrPostprocess.dictionary("  character_dict:\n" + List(836) { "  - '" }.joinToString("\n")) }
    }

    @Test fun ctcCollapsesOnlyAdjacentRepeatsAndPreservesAccentsAndCurrency() {
        val dictionary = listOf("", "l", "e", "ć", "’", "$", "9", ".", "3", " ")
        val indices = listOf(1, 1, 0, 1, 2, 2, 3, 4, 9, 5, 6, 7, 8)
        val logits = FloatArray(indices.size * dictionary.size) { .01f }
        indices.forEachIndexed { step, label -> logits[step * dictionary.size + label] = .9f }
        val result = PaddleOcrPostprocess.decode(logits, indices.size, dictionary.size, dictionary)
        assertEquals("lleć’ $9.3", result.text)
        assertEquals(.9f, result.confidence, .00001f)
    }

    @Test fun ctcRejectsWrongShapeAndNonfiniteOutputButAllowsAllBlank() {
        val dictionary = listOf("", "x")
        assertEquals("", PaddleOcrPostprocess.decode(floatArrayOf(.9f, .1f), 1, 2, dictionary).text)
        rejected { PaddleOcrPostprocess.decode(floatArrayOf(Float.NaN, .1f), 1, 2, dictionary) }
        rejected { PaddleOcrPostprocess.decode(floatArrayOf(.9f), 1, 2, dictionary) }
        rejected { PaddleOcrPostprocess.decode(floatArrayOf(.9f, .1f), 1, 3, dictionary) }
    }

    @Test fun detectionResizesWithinBudgetToModelStride() {
        assertEquals(960 to 640, PaddleOcrPostprocess.detectorSize(3000, 2000, 960))
        assertEquals(32 to 960, PaddleOcrPostprocess.detectorSize(2, 2000, 960))
        assertEquals(64 to 32, PaddleOcrPostprocess.detectorSize(64, 32, 960))
        rejected { PaddleOcrPostprocess.detectorSize(0, 100, 960) }
    }

    @Test fun dbUnclipsDetectedInkAndMapsBackToSourceCoordinates() {
        val map = FloatArray(64 * 32)
        for (y in 10..17) for (x in 12..39) map[y * 64 + x] = .9f
        val quad = PaddleOcrPostprocess.detect(map, 64, 32, 640, 320).single()
        assertEquals(.9f, quad.confidence, .00001f)
        assertTrue(quad.bounds.left < 120f && quad.bounds.right > 390f)
        assertTrue(quad.bounds.top < 100f && quad.bounds.bottom > 170f)
        assertTrue(quad.points[0].x < quad.points[1].x && quad.points[0].y < quad.points[3].y)
    }

    @Test fun dbScoreIncludesBackgroundInsideContourRatherThanOnlyConfidentPixels() {
        val map = FloatArray(64 * 32)
        for (y in 8..23) for (x in 10..49) if (y == 8 || y == 23 || x == 10 || x == 49) map[y * 64 + x] = .99f
        assertTrue(PaddleOcrPostprocess.detect(map, 64, 32, 64, 32).isEmpty())
    }

    @Test fun dbHandlesBlankClippedEdgesAndCancellation() {
        assertTrue(PaddleOcrPostprocess.detect(FloatArray(32 * 32), 32, 32, 32, 32).isEmpty())
        val quad = PaddleOcrPostprocess.detect(FloatArray(32 * 32) { .9f }, 32, 32, 100, 100).single()
        assertTrue(quad.points.all { it.x in 0f..100f && it.y in 0f..100f })
        rejected { PaddleOcrPostprocess.detect(floatArrayOf(Float.POSITIVE_INFINITY), 1, 1, 1, 1) }
        var called = false
        try {
            PaddleOcrPostprocess.detect(FloatArray(32 * 32), 32, 32, 32, 32) { called = true; throw InterruptedException() }
            fail("Cancellation must interrupt scanning")
        } catch (_: InterruptedException) { assertTrue(called) }
    }

    @Test fun accentsRemainRecognizedTextWithoutBorrowingDifferentWordGeometry() {
        val refs = listOf(reference("Milankovic’s", 10f), reference("theory", 80f))
        val result = PaddleOcrPostprocess.words("Milanković’s theory", region, refs, 2)
        assertEquals("Milanković’s", result[0].text)
        assertTrue(result[0].boxes.isEmpty())
        assertEquals(refs[1].boxes, result[1].boxes)
        assertEquals(2, result[0].block)
    }

    @Test fun repeatedWordsMatchOnlyWithCompleteSequenceAgreement() {
        val refs = listOf(reference("the", 10f), reference("one", 40f), reference("the", 80f))
        val full = PaddleOcrPostprocess.words("the one the", region, refs, 0)
        assertEquals(refs.map { it.boxes }, full.map { it.boxes })
        val partial = PaddleOcrPostprocess.words("the one", region, refs, 0)
        assertTrue(partial[0].boxes.isEmpty())
        assertEquals(refs[1].boxes, partial[1].boxes)
    }

    @Test fun overlappingRegionsDoNotReuseTheSameObservedGeometry() {
        val refs = listOf(reference("word", 10f), reference("outside", 500f))
        val used = mutableSetOf<TextElement>()
        assertTrue(PaddleOcrPostprocess.words("word outside", region, refs, 0, used)[0].boxes.isNotEmpty())
        val next = PaddleOcrPostprocess.words("word outside", region, refs, 1, used)
        assertTrue(next.all { it.boxes.isEmpty() })
    }

    @Test fun unicodeNormalizationPermitsEquivalentGlyphsButNeverChangedNumbers() {
        val refs = listOf(reference("c\u0301", 10f), reference("$9.3", 50f))
        val result = PaddleOcrPostprocess.words("ć $8.3", region, refs, 0)
        assertEquals(refs[0].boxes, result[0].boxes)
        assertTrue(result[1].boxes.isEmpty())
    }
}
