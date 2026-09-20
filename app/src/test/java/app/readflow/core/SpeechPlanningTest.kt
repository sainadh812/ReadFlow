package app.readflow.core

import org.junit.Assert.*
import org.junit.Test

class SpeechPlanningTest {
    private fun page(text: String) = DocumentPipeline().process("planning-fixture", 0,
        ExtractedPage(listOf(TextElement(text, emptyList())), 0f, 0f, "Saved article"))

    @Test fun laterEquationDoesNotPreventEarlierSentencePreparation() {
        val words = page("Read this first. Next x = y. Read this later.").words
        val plans = SpeechPlanner().prepare(words).iterator()
        assertEquals("Read this first.", plans.next().speech.text)
        val error = assertThrows(SpeechPreparationException::class.java) { plans.hasNext() }
        assertEquals("Next", error.sentence.first().text)
        assertEquals("y.", error.sentence.last().text)
    }
    @Test fun jumpingPastAnEquationDoesNotNormalizeIt() {
        val words = page("First x = y. Read read again.").words
        val requested = words.first { it.text == "read" }
        val chunks = SpeechPlanner().prepare(words, requested.id).toList()
        assertEquals("Read read again.", chunks.single().speech.text)
        assertTrue(chunks.first().speech.tokens.any { requested.id in it.sourceIds })
        assertEquals(words.takeLast(3), chunks.flatMap { it.words })
    }
    @Test fun oversizedEarlierTokenDoesNotPreventLaterWordJump() {
        val words = page("${"x".repeat(300)}. Read this later.").words
        assertEquals("Read this later.", SpeechPlanner().prepare(words, words.last().id).single().speech.text)
    }
    @Test fun preparationOnlyVisitsRequestedLookAhead() {
        var calls = 0
        val normalizer = object : TextNormalizer {
            override fun normalize(words: List<SourceWord>): SpeechText {
                calls++
                return EnglishNormalizer().normalize(words)
            }
        }
        val plans = SpeechPlanner(normalizer).prepare(page("First sentence. Second sentence. Third sentence.").words).iterator()
        assertEquals(0, calls)
        plans.next()
        assertEquals(1, calls)
    }
    @Test fun expandedLongSentencesStayBoundedAndKeepAllWords() {
        val words = page((1..80).joinToString(" ") { "999999999999" } + ".").words
        val chunks = SpeechPlanner().prepare(words).toList()
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.speech.text.length <= 220 })
        assertEquals(words, chunks.flatMap { it.words })
    }
    @Test fun requestInsideSplitSentenceKeepsTheContainingChunk() {
        val words = page((1..80).joinToString(" ") { "999999999999" } + ".").words
        val requested = words[40]
        val chunks = SpeechPlanner().prepare(words, requested.id).toList()
        assertTrue(chunks.first().words.contains(requested))
        assertEquals(words.last(), chunks.last().words.last())
    }
    @Test fun skipUsesFailedSentenceNotCurrentlyPlayingSentence() {
        val words = page("Read first. Then x = y. Read last.").words
        val failed = words.first { it.text == "Then" }
        val target = afterSentence(words, failed.id, 0, 1)!!
        assertEquals(words[6].id, target.wordId)
        assertEquals("Read last.", SpeechPlanner().prepare(words, target.wordId).first().speech.text)
    }
    @Test fun skipAtPageBoundaryGoesToNextPageOrEnd() {
        val words = page("Read first. Then x = y.").words
        assertEquals(SpeechTarget(1, null), afterSentence(words, words.last().id, 0, 2))
        assertNull(afterSentence(words, words.last().id, 1, 2))
        assertNull(afterSentence(words, "stale-word", 0, 2))
    }
    @Test fun normalizationRevisionInvalidatesAudioWithoutChangingSourceIds() {
        val words = page("Read here.").words
        val speech = EnglishNormalizer().normalize(words)
        assertNotEquals(cacheKey("doc", speech, "model", "voice", "settings", "align", "english-1"),
            cacheKey("doc", speech, "model", "voice", "settings", "align"))
        assertEquals(words.map { it.id }, speech.sourceIds)
        assertEquals(words, page("Read here.").words)
    }
}
