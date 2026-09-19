package app.readflow.core

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private fun page(text: String) = DocumentPipeline().process("fixture", 0, ExtractedPage(listOf(TextElement(text, emptyList())), 600f, 800f, "fixture"))
    @Test fun repeatedWordsHaveStableDistinctIdsAndUtf16Offsets() {
        val a = page("Read read. A \uD83D\uDCD6 book.")
        val b = page("Read read. A \uD83D\uDCD6 book.")
        assertEquals(a, b)
        assertEquals(a.words.size, a.words.map { it.id }.distinct().size)
        a.words.forEach { assertEquals(it.text, a.reading.substring(it.start, it.end)) }
    }
    @Test fun expansionMapsSeveralSpokenWordsToOriginalToken() {
        val p = page("Pay ₹20,000 for 12kg.")
        val speech = EnglishNormalizer().normalize(p.words)
        assertEquals("Pay twenty thousand rupees for twelve kilograms.", speech.text)
        assertEquals(3, speech.tokens.count { p.words[1].id in it.sourceIds })
    }
    @Test fun transformsRoundTripRotationZoomAndTranslation() {
        val transform = Transform(0f, 2f, -2f, 0f, 600f, 40f)
        val point = transform.map(12f, 72f)
        val restored = transform.inverse().map(point.first, point.second)
        assertEquals(12f, restored.first, .001f); assertEquals(72f, restored.second, .001f)
    }
    @Test fun twoColumnsReadLeftThenRight() {
        val words = (0..5).map { TextElement("$it", listOf(Box(if (it % 2 == 0) 20f else 330f, (it / 2) * 30f, if (it % 2 == 0) 200f else 550f, (it / 2) * 30f + 20)), it, it) }
        assertEquals(listOf("0", "2", "4", "1", "3", "5"), GeometricReadingOrder().order(words, 600f).map { it.text })
    }
    @Test fun longSentenceSplittingNeverDropsWords() {
        val words = page((1..200).joinToString(" ") { "reading" }).words
        val chunks = chunkWords(words)
        assertTrue(chunks.size > 1); assertEquals(words, chunks.flatten())
    }
    @Test fun cacheIncludesVoiceSettingsRevisionAndAlignment() {
        val s = EnglishNormalizer().normalize(page("Hello there.").words)
        val a = cacheKey("doc", s, "kokoro-v0.19", "af", "speed=1", "align-v1")
        assertNotEquals(a, cacheKey("doc", s, "pocket-v1", "af", "speed=1", "align-v1"))
        assertNotEquals(a, cacheKey("doc", s, "kokoro-v0.19", "am", "speed=1", "align-v1"))
        assertNotEquals(a, cacheKey("doc", s, "kokoro-v0.19", "af", "speed=1", "align-v2"))
    }
    @Test fun mediaPositionHandlesSilenceAndDoesNotApplySpeedTwice() {
        val ts = listOf(WordTiming("a", 2400, 4800, 1f), WordTiming("b", 7200, 9600, 1f))
        assertNull(activeWord(ts, 0, 24000)); assertEquals("a", activeWord(ts, 150, 24000)?.wordId)
        assertNull(activeWord(ts, 250, 24000)); assertEquals("b", activeWord(ts, 350, 24000)?.wordId)
    }
    @Test fun ctcRepeatedLettersRequireRealBlankFrames() {
        val vocab = CtcAlignment.VOCAB
        val labels = listOf(0, vocab.indexOf("A"), 0, vocab.indexOf("L"), 0, vocab.indexOf("L"), 0)
        val logits = labels.map { label -> FloatArray(32) { if (it == label) 8f else -8f } }.toTypedArray()
        val aligned = CtcAlignment().align(logits, SpeechText("all", listOf(SpokenToken("ALL", listOf("w"))), listOf("w")), 16000, 2640)
        assertEquals(320L, aligned.single().startSample)
        assertEquals(2000L, aligned.single().endSample)
    }
    @Test fun lowConfidenceAlignmentFailsClosed() {
        try {
            CtcAlignment().align(Array(12) { FloatArray(32) }, SpeechText("a", listOf(SpokenToken("A", listOf("w"))), listOf("w")), 16000, 4200)
            fail("Expected alignment failure")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun abbreviationDoesNotSeparateTheTitleFromItsSentence() {
        val p = page("Dr. Green recorded twelve kilograms. Reading continues.")
        assertEquals(p.words[0].sentenceId, p.words[1].sentenceId)
        assertNotEquals(p.words[1].sentenceId, p.words.last().sentenceId)
    }
    @Test fun hyphenatedLineFragmentsKeepBothSourceMappingsAndHighlights() {
        val p = DocumentPipeline().process("hyphen", 0, ExtractedPage(listOf(
            TextElement("co-", listOf(Box(10f, 10f, 40f, 20f)), 0, 0),
            TextElement("operate", listOf(Box(10f, 30f, 70f, 40f)), 0, 1)), 200f, 300f, "fixture"))
        val speech = EnglishNormalizer().normalize(p.words)
        assertEquals("cooperate", speech.text)
        assertEquals(p.words.map { it.id }, speech.tokens.single().sourceIds)
        val timings = p.words.map { WordTiming(it.id, 160, 1600, 1f) }
        assertEquals(p.words.map { it.id }.toSet(), activeWordIds(timings, 20, 16000))
    }
    @Test fun largeNumericValuesFailWithoutOverflowOrTruncation() {
        try { EnglishNormalizer().normalize(page("-9223372036854775808").words); fail("Unbounded numeral should be rejected") }
        catch (_: IllegalArgumentException) { }
    }
}
