package app.readflow.playback

import app.readflow.core.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SentenceFailuresTest {
    private fun page(index: Int = 0, document: String = "doc", text: String = "one two three|four five"): PageContent {
        var offset = 0
        val words = text.split('|').flatMapIndexed { sentence, span ->
            span.split(' ').map { word ->
                val start = offset
                offset += word.length + 1
                SourceWord("$document/$index/$start", word, start, offset - 1, start, offset - 1,
                    start, "paragraph", "sentence-$sentence")
            }
        }
        return PageContent("$document/$index", document, index, text, text, words, emptyList(), 100f, 100f, "fixture")
    }
    private fun tracker(maxFailures: Int = 1024) = SentenceFailureTracker(maxFailures).apply { openDocument("doc") }
    private val synthesisError get() = IllegalStateException("Invalid generated samples")
    private fun fail(tracker: SentenceFailureTracker, token: Long, page: PageContent, words: List<SourceWord> = page.words.take(1)) =
        tracker.failed(token, page, words, "SYNTHESIZE", synthesisError)

    @Test fun aFailedChunkMarksItsWholeSentenceOnTheProcessingPage() {
        val tracker = tracker()
        val token = tracker.beginAttempt()
        val current = page(0)
        val prefetched = page(3)
        assertTrue(fail(tracker, token, prefetched, prefetched.words.slice(1..2)))
        assertEquals(setOf("sentence-0"), failedSentenceIds(prefetched, tracker.snapshot))
        assertTrue(failedSentenceIds(current, tracker.snapshot).isEmpty())
    }

    @Test fun laterNormalizationFailureUsesExceptionSentenceRatherThanPreviousChunk() {
        val tracker = tracker()
        val token = tracker.beginAttempt()
        val page = page()
        val failedWords = page.words.drop(3)
        val failure = SpeechPreparationException(failedWords, IllegalArgumentException("Unsupported input"))
        assertTrue(tracker.failed(token, page, page.words.take(3), "NORMALIZE_TEXT", failure))
        assertEquals(setOf("sentence-1"), failedSentenceIds(page, tracker.snapshot))
    }

    @Test fun normalizationFailureIsIncludedButUnrelatedPreparationStagesAreExcluded() {
        val words = page().words.take(1)
        val error = SpeechPreparationException(words, IllegalArgumentException("Unsupported input"))
        assertTrue(isSentenceVoiceFailure("NORMALIZE_TEXT", error))
        assertTrue(isSentenceVoiceFailure("SYNTHESIZE", synthesisError))
        for (stage in listOf("VERIFY_MODELS", "LOAD_MODEL", "EXTRACT_PAGE", "READ_AUDIO_CACHE", "WRITE_AUDIO",
            "CHECK_AUDIO_STORAGE", "ALIGN_WORDS", "QUEUE_AUDIO", "MEDIA_PLAYBACK", "RELEASE_MODELS")) {
            assertFalse(stage, isSentenceVoiceFailure(stage, IOException("Unavailable")))
        }
        assertFalse(isSentenceVoiceFailure("NORMALIZE_TEXT", IOException("Diagnostic storage failed")))
    }

    @Test fun cancellationAndLazyRuntimeInitializationDoNotMarkSourceText() {
        assertFalse(isSentenceVoiceFailure("SYNTHESIZE", CancellationException("Stopped")))
        assertFalse(isSentenceVoiceFailure("SYNTHESIZE", IllegalStateException("Wrapped", CancellationException())))
        assertFalse(isSentenceVoiceFailure("SYNTHESIZE", SpeechRuntimeInitializationException(IllegalStateException("Model init failed"))))
        assertFalse(isSentenceVoiceFailure("SYNTHESIZE", IllegalStateException("Native load failed", UnsatisfiedLinkError())))
    }

    @Test fun onlyWholeSentenceSuccessClearsAMarkAcrossChunks() {
        val tracker = tracker()
        val page = page()
        fail(tracker, tracker.beginAttempt(), page)
        val retry = tracker.beginAttempt()
        assertFalse(tracker.succeeded(retry, page, page.words.take(1)))
        assertEquals(setOf("sentence-0"), failedSentenceIds(page, tracker.snapshot))
        assertTrue(tracker.succeeded(retry, page, page.words.slice(1..2)))
        assertTrue(tracker.snapshot.isEmpty())
    }

    @Test fun aSelectedSuffixCannotClearTheWholeSentence() {
        val tracker = tracker()
        val page = page()
        fail(tracker, tracker.beginAttempt(), page)
        assertFalse(tracker.succeeded(tracker.beginAttempt(), page, page.words.slice(1..2)))
        assertFalse(tracker.snapshot.isEmpty())
        assertTrue(tracker.succeeded(tracker.beginAttempt(), page, page.words.take(3)))
        assertTrue(tracker.snapshot.isEmpty())
    }

    @Test fun recoveryCoverageDoesNotCarryOverBetweenAttempts() {
        val tracker = tracker()
        val page = page()
        fail(tracker, tracker.beginAttempt(), page)
        tracker.succeeded(tracker.beginAttempt(), page, page.words.take(1))
        assertFalse(tracker.succeeded(tracker.beginAttempt(), page, page.words.slice(1..2)))
        assertFalse(tracker.snapshot.isEmpty())
    }

    @Test fun aFullEligibleSentenceClearsWhenMarginWordsAreExcluded() {
        val tracker = tracker()
        val source = page()
        val page = source.copy(words = source.words.mapIndexed { index, word -> word.copy(marginal = index == 0) })
        fail(tracker, tracker.beginAttempt(), page, page.words.slice(1..2))
        assertTrue(tracker.succeeded(tracker.beginAttempt(), page, page.words.slice(1..2), skipMargins = true))
        assertTrue(tracker.snapshot.isEmpty())
    }

    @Test fun excludingAMiddleMarginWordStillRequiresEveryEligibleChunk() {
        val tracker = tracker()
        val source = page()
        val page = source.copy(words = source.words.mapIndexed { index, word -> word.copy(marginal = index == 1) })
        fail(tracker, tracker.beginAttempt(), page)
        val attempt = tracker.beginAttempt()
        assertFalse(tracker.succeeded(attempt, page, page.words.take(1), skipMargins = true))
        assertTrue(tracker.succeeded(attempt, page, listOf(page.words[2]), skipMargins = true))
        assertTrue(tracker.snapshot.isEmpty())
    }

    @Test fun skippingMarginsDoesNotAllowASelectedSuffixToClearEarlierEligibleWords() {
        val tracker = tracker()
        val source = page()
        val page = source.copy(words = source.words.mapIndexed { index, word -> word.copy(marginal = index == 0) })
        fail(tracker, tracker.beginAttempt(), page, page.words.slice(1..2))
        assertFalse(tracker.succeeded(tracker.beginAttempt(), page, listOf(page.words[2]),
            skipMargins = true, selectedWordId = page.words[2].id))
        assertFalse(tracker.snapshot.isEmpty())
    }

    @Test fun anExplicitlySelectedMarginalWordMustAlsoSucceed() {
        val tracker = tracker()
        val source = page()
        val page = source.copy(words = source.words.mapIndexed { index, word -> word.copy(marginal = index == 0) })
        fail(tracker, tracker.beginAttempt(), page)
        assertFalse(tracker.succeeded(tracker.beginAttempt(), page, page.words.slice(1..2),
            skipMargins = true, selectedWordId = page.words[0].id))
        assertTrue(tracker.succeeded(tracker.beginAttempt(), page, page.words.take(3),
            skipMargins = true, selectedWordId = page.words[0].id))
        assertTrue(tracker.snapshot.isEmpty())
    }

    @Test fun aLaterChunkFailureDiscardsPartialRecovery() {
        val tracker = tracker()
        val page = page()
        fail(tracker, tracker.beginAttempt(), page)
        val retry = tracker.beginAttempt()
        tracker.succeeded(retry, page, page.words.take(1))
        fail(tracker, retry, page, page.words.slice(1..2))
        assertFalse(tracker.succeeded(retry, page, page.words.slice(1..2)))
        assertFalse(tracker.snapshot.isEmpty())
    }

    @Test fun stopAndPageNavigationKeepFailuresButRejectLateCallbacks() {
        val tracker = tracker()
        val page = page()
        val old = tracker.beginAttempt()
        fail(tracker, old, page)
        tracker.cancelAttempt()
        tracker.openDocument("doc")
        tracker.observePage(page(1))
        assertEquals(setOf("sentence-0"), failedSentenceIds(page, tracker.snapshot))
        assertFalse(tracker.succeeded(old, page, page.words.take(3)))
        assertFalse(fail(tracker, old, page(1)))
        assertEquals(1, tracker.snapshot.size)
    }

    @Test fun changedTextWithStableIdsImmediatelyHidesAndThenInvalidatesOldMarks() {
        val tracker = tracker()
        val page = page()
        fail(tracker, tracker.beginAttempt(), page)
        val changed = page.copy(reading = "corrected", words = page.words.map { it.copy(text = "new") })
        assertTrue(failedSentenceIds(changed, tracker.snapshot).isEmpty())
        assertTrue(tracker.observePage(changed))
        assertTrue(tracker.snapshot.isEmpty())
    }

    @Test fun explicitReprocessingInvalidatesOnlyTheRequestedPage() {
        val tracker = tracker()
        val attempt = tracker.beginAttempt()
        fail(tracker, attempt, page(0))
        fail(tracker, attempt, page(1))
        assertFalse(tracker.invalidatePage("other", 0))
        assertTrue(tracker.invalidatePage("doc", 0))
        assertEquals(setOf(1), tracker.snapshot.map { it.pageIndex }.toSet())
    }

    @Test fun changingDocumentsOrDocumentRevisionResetsMarkersAndPendingWork() {
        val tracker = tracker()
        val old = tracker.beginAttempt()
        val page = page()
        fail(tracker, old, page)
        tracker.openDocument("other")
        assertTrue(tracker.snapshot.isEmpty())
        assertFalse(fail(tracker, old, page))
        tracker.openDocument("doc", "revision-2")
        fail(tracker, tracker.beginAttempt(), page)
        tracker.openDocument("doc", "revision-3")
        assertTrue(tracker.snapshot.isEmpty())
    }

    @Test fun sameSentenceAndPageIdsInAnotherDocumentNeverMatch() {
        val page = page()
        val failure = FailedSentence("different", page.index, sentenceFailureRevision(page), "sentence-0")
        assertTrue(failedSentenceIds(page, setOf(failure)).isEmpty())
    }

    @Test fun failureHistoryIsBoundedAndRetainsTheNewestFailures() {
        val tracker = tracker(maxFailures = 2)
        val attempt = tracker.beginAttempt()
        for (index in 0..3) fail(tracker, attempt, page(index))
        assertEquals(setOf(2, 3), tracker.snapshot.map { it.pageIndex }.toSet())
    }

    @Test fun wordsFromAnObsoletePageCannotMarkTheReplacementSentence() {
        val tracker = tracker()
        val old = page()
        val replacement = old.copy(words = old.words.map { it.copy(text = "corrected") })
        assertFalse(fail(tracker, tracker.beginAttempt(), replacement, old.words.take(1)))
        assertTrue(tracker.snapshot.isEmpty())
    }
}
