package app.readflow.playback

import app.readflow.core.NormalizationException
import app.readflow.core.PageContent
import app.readflow.core.SourceWord
import app.readflow.core.SpeechPreparationException
import app.readflow.core.stableId
import kotlinx.coroutines.CancellationException

/** A narration failure belongs to source text, independently of the current playback cursor. */
data class FailedSentence(
    val documentId: String,
    val pageIndex: Int,
    val pageRevision: String,
    val sentenceId: String,
)

/** Page and sentence IDs survive OCR replacement, so they cannot alone identify failed text. */
fun sentenceFailureRevision(page: PageContent): String = stableId(page.id, page.reading,
    page.words.joinToString("\u0000") { "${it.id}\u0000${it.sentenceId}\u0000${it.text}\u0000${it.line}\u0000${it.marginal}" })

fun failedSentenceIds(page: PageContent, failures: Set<FailedSentence>): Set<String> {
    val matching = failures.filter { it.documentId == page.documentId && it.pageIndex == page.index }
    if (matching.isEmpty()) return emptySet()
    val revision = sentenceFailureRevision(page)
    return matching.filter { it.pageRevision == revision }.mapTo(linkedSetOf()) { it.sentenceId }
}

/** Lazy frontend construction occurs during SYNTHESIZE but is not a failure of source text. */
internal class SpeechRuntimeInitializationException(cause: Exception) : RuntimeException(cause.message, cause)

internal fun isSentenceVoiceFailure(stage: String, error: Throwable): Boolean {
    // Examine a bounded chain even if a native/runtime wrapper has a malformed cyclic cause.
    var cause: Throwable? = error
    repeat(16) {
        val current = cause ?: return@repeat
        if (current is CancellationException || current is SpeechRuntimeInitializationException || current is LinkageError)
            return false
        cause = current.cause
    }
    return stage == "SYNTHESIZE" || (stage == "NORMALIZE_TEXT" &&
        (error is SpeechPreparationException || error is NormalizationException))
}

/**
 * Main-thread ledger. It retains at most [maxFailures] failures for the open document,
 * one observed page and one sentence's recovery counter, never PCM or historical pages.
 * Attempt tokens prevent late work from a stopped/replaced producer mutating current state.
 */
internal class SentenceFailureTracker(private val maxFailures: Int = 1024) {
    init { require(maxFailures > 0) }
    private var documentId: String? = null
    private var documentRevision: String? = null
    private var attempt = 0L
    private val failed = linkedMapOf<Pair<Int, String>, FailedSentence>()
    private var observedPage: PageContent? = null
    private var observedRevision: String? = null
    private data class Recovery(val failure: FailedSentence, val skipMargins: Boolean,
        val selectedWordId: String?, var completedWords: Int = 0)
    private var recovery: Recovery? = null
    val snapshot: Set<FailedSentence> get() = failed.values.toSet()

    fun openDocument(id: String?, revision: String? = id) {
        if (documentId == id && documentRevision == revision) return
        cancelAttempt()
        documentId = id; documentRevision = revision
        failed.clear(); observedPage = null; observedRevision = null
    }

    fun beginAttempt(): Long { cancelAttempt(); return attempt }
    fun cancelAttempt() { attempt++; recovery = null }

    fun invalidatePage(id: String, pageIndex: Int): Boolean {
        if (documentId != id) return false
        val changed = failed.keys.removeAll { it.first == pageIndex }
        if (recovery?.failure?.pageIndex == pageIndex) recovery = null
        if (observedPage?.index == pageIndex) { observedPage = null; observedRevision = null }
        return changed
    }

    fun observePage(page: PageContent): Boolean {
        if (page.documentId != documentId || observedPage === page) return false
        val revision = sentenceFailureRevision(page)
        observedPage = page; observedRevision = revision
        val changed = failed.entries.removeAll { it.key.first == page.index && it.value.pageRevision != revision }
        if (recovery?.failure?.let { it.pageIndex == page.index && it.pageRevision != revision } == true) recovery = null
        return changed
    }

    fun failed(token: Long, page: PageContent, words: List<SourceWord>, stage: String, error: Throwable): Boolean {
        if (token != attempt || page.documentId != documentId || !isSentenceVoiceFailure(stage, error)) return false
        var changed = observePage(page)
        val revision = checkNotNull(observedRevision)
        val currentWords = page.words.associateBy { it.id }
        val failedWords = (error as? SpeechPreparationException)?.sentence ?: words
        val sentenceIds = failedWords.mapNotNull { word -> currentWords[word.id]?.takeIf { it == word }?.sentenceId }.distinct()
        for (sentenceId in sentenceIds) {
            val key = page.index to sentenceId
            val failure = FailedSentence(page.documentId, page.index, revision, sentenceId)
            if (failed[key] != failure) { failed[key] = failure; changed = true }
            if (recovery?.failure == failure) recovery = null
        }
        while (failed.size > maxFailures) failed.remove(failed.keys.first())
        return changed
    }

    /** Call only after validated cached audio or successful synthesis, including during prefetch. */
    fun succeeded(token: Long, page: PageContent, words: List<SourceWord>,
        skipMargins: Boolean = false, selectedWordId: String? = null): Boolean {
        if (token != attempt || page.documentId != documentId) return false
        var changed = observePage(page)
        for ((sentenceId, completed) in words.groupBy { it.sentenceId }) {
            val key = page.index to sentenceId
            val failure = failed[key] ?: continue
            val progress = recovery?.takeIf { it.failure == failure && it.skipMargins == skipMargins &&
                it.selectedWordId == selectedWordId } ?: Recovery(failure, skipMargins, selectedWordId).also { recovery = it }
            // Match the page's narration policy, including an explicitly selected margin word.
            // Derive the entire eligible sentence here: the caller's selected suffix is not enough.
            val sentence = page.words.asSequence().filter { it.sentenceId == sentenceId &&
                (!skipMargins || !it.marginal || it.id == selectedWordId) }
            val expected = sentence.drop(progress.completedWords).take(completed.size).toList()
            if (expected != completed) { recovery = null; continue }
            progress.completedWords += completed.size
            if (progress.completedWords == sentence.count()) {
                failed.remove(key); recovery = null; changed = true
            }
        }
        return changed
    }
}
