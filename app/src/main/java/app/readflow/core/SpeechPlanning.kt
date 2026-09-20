package app.readflow.core

data class PreparedSpeech(val words: List<SourceWord>, val speech: SpeechText)
class SpeechPreparationException(val sentence: List<SourceWord>, cause: IllegalArgumentException) :
    IllegalArgumentException(cause.message, cause)

data class SpeechTarget(val pageIndex: Int, val wordId: String?)

fun afterSentence(words: List<SourceWord>, wordId: String, pageIndex: Int, pageCount: Int): SpeechTarget? {
    val word = words.firstOrNull { it.id == wordId } ?: return null
    val last = words.indexOfLast { it.sentenceId == word.sentenceId }
    val next = words.getOrNull(last + 1)
    return when {
        next != null -> SpeechTarget(pageIndex, next.id)
        pageIndex + 1 < pageCount -> SpeechTarget(pageIndex + 1, null)
        else -> null
    }
}

class SpeechPlanner(private val normalizer: TextNormalizer = EnglishNormalizer(), private val maxCharacters: Int = 220) {
    // Only visit the requested sentence and its successors. A later failure must not block earlier audio.
    fun prepare(words: List<SourceWord>, requestedWordId: String? = null): Sequence<PreparedSpeech> = sequence {
        val sentences = words.groupBy { it.sentenceId }.values.toList()
        val start = if (requestedWordId == null) 0 else sentences.indexOfFirst { sentence -> sentence.any { it.id == requestedWordId } }
        require(start >= 0) { "The selected word is no longer on this page." }
        var seeking = requestedWordId != null
        for (sentence in sentences.drop(start)) {
            try {
                for (group in chunkWords(sentence, maxCharacters)) {
                    for (prepared in split(group)) {
                        if (seeking && prepared.words.none { it.id == requestedWordId }) continue
                        seeking = false
                        yield(prepared)
                    }
                }
            } catch (error: IllegalArgumentException) {
                throw SpeechPreparationException(sentence, error)
            }
        }
    }

    private fun split(words: List<SourceWord>): Sequence<PreparedSpeech> = sequence {
        val speech = normalizer.normalize(words)
        if (speech.text.length <= maxCharacters) yield(PreparedSpeech(words, speech))
        else {
            require(words.size > 1) { "This token exceeds the speech model limit; it was not truncated." }
            val middle = words.size / 2
            yieldAll(split(words.take(middle)))
            yieldAll(split(words.drop(middle)))
        }
    }
}
