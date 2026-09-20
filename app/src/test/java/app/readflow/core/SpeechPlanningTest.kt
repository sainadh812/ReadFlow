package app.readflow.core

import org.junit.Assert.*
import org.junit.Test

class SpeechPlanningTest {
    private fun page(text: String) = DocumentPipeline().process("planning-fixture", 0,
        ExtractedPage(listOf(TextElement(text, emptyList())), 0f, 0f, "Saved article"))
    private fun lineWords(vararg text: String) = DocumentPipeline().process("hyphen-planning", 0,
        ExtractedPage(text.mapIndexed { index, word ->
            TextElement(word, listOf(Box(10f, index * 30f, 80f, index * 30f + 20)), 0, index)
        }, 200f, 300f, "fixture")).words

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
    @Test fun playbackJumpSynthesizesExactlyFromTheSelectedWordWithoutEarlierText() {
        val words = page("Read read again. Then continue.").words
        val requested = words[1]
        val chunks = SpeechPlanner().prepare(words, requested.id, fromSelectedWord = true).toList()
        assertEquals("read again.", chunks.first().speech.text)
        assertEquals(words.drop(1), chunks.flatMap { it.words })
        assertEquals(requested.id, chunks.first().speech.tokens.first().sourceIds.first())
        assertFalse(chunks.any { words.first().id in it.speech.sourceIds })
    }
    @Test fun exactPlaybackJumpBypassesUnsupportedEarlierTextInTheSameSentence() {
        val words = page("x = y then read here.").words
        val requested = words.first { it.text == "read" }
        val chunks = SpeechPlanner().prepare(words, requested.id, fromSelectedWord = true).toList()
        assertEquals("read here.", chunks.single().speech.text)
    }
    @Test fun exactPlaybackJumpStillChunksExpandedTextWithoutDroppingTheTail() {
        val words = page((1..80).joinToString(" ") { "999999999999" } + ".").words
        val chunks = SpeechPlanner().prepare(words, words[40].id, fromSelectedWord = true).toList()
        assertTrue(chunks.all { it.speech.text.length <= 220 })
        assertEquals(words.drop(40), chunks.flatMap { it.words })
    }
    @Test fun playbackJumpToSecondHyphenFragmentKeepsTheWholeSpokenWord() {
        val words = lineWords("co-", "operate", "later.")
        val requested = words[1]
        val chunks = SpeechPlanner().prepare(words, requested.id, fromSelectedWord = true).toList()
        assertEquals("cooperate later.", chunks.single().speech.text)
        assertEquals(words, chunks.flatMap { it.words })
        assertEquals(words.take(2).map { it.id }, chunks.first().speech.tokens.first().sourceIds)
        assertTrue(requested.id in chunks.first().speech.tokens.first().sourceIds)
    }
    @Test fun selectionUsesTheSameHyphenPairConsumptionAsNormalization() {
        val words = lineWords("co-", "op-", "erate")
        val speech = EnglishNormalizer().normalize(words)
        assertEquals(words.take(2).map { it.id }, speech.tokens.first().sourceIds)
        assertEquals(listOf(words[2].id), speech.tokens.last().sourceIds)
        val chunks = SpeechPlanner().prepare(words, words[2].id, fromSelectedWord = true).toList()
        assertEquals("erate", chunks.single().speech.text)
        assertEquals(words.takeLast(1), chunks.single().words)
    }
    @Test fun rawAndExpandedChunkLimitsDoNotSplitJoinedSourceWords() {
        val short = lineWords("co-", "operate", "later")
        val shortChunks = SpeechPlanner(maxCharacters = 10).prepare(short, short[1].id, fromSelectedWord = true).toList()
        assertEquals(listOf("cooperate", "later"), shortChunks.map { it.speech.text })
        assertEquals(short.take(2).map { it.id }, shortChunks.first().speech.tokens.first().sourceIds)

        val expanded = lineWords("Read", "co-", "operate", "12345", "12345")
        val expandedChunks = SpeechPlanner(maxCharacters = 50).prepare(expanded).toList()
        assertTrue(expandedChunks.all { it.speech.text.length <= 50 })
        assertEquals(expanded, expandedChunks.flatMap { it.words })
        val joined = expandedChunks.flatMap { it.speech.tokens }.single { it.text == "COOPERATE" }
        assertEquals(expanded.subList(1, 3).map { it.id }, joined.sourceIds)
    }
    @Test fun anOversizedJoinedWordFailsWithoutTruncatingItsFragments() {
        val words = lineWords("co-", "operate")
        val error = assertThrows(SpeechPreparationException::class.java) {
            SpeechPlanner(maxCharacters = 8).prepare(words, words[1].id, fromSelectedWord = true).toList()
        }
        assertEquals(words, error.sentence)
        assertTrue(error.message!!.contains("not truncated"))
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
        assertNotEquals(cacheKey("doc", speech, "model", "voice", "settings", "align", "english-2"),
            cacheKey("doc", speech, "model", "voice", "settings", "align"))
        assertEquals(words.map { it.id }, speech.sourceIds)
        assertEquals(words, page("Read here.").words)
    }
}
