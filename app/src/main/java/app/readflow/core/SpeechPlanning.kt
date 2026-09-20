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
    private val sourceGrouper = normalizer as? EnglishNormalizer ?: EnglishNormalizer()
    // Only visit the requested sentence and its successors. A later failure must not block earlier audio.
    fun prepare(words: List<SourceWord>, requestedWordId: String? = null, fromSelectedWord: Boolean = false): Sequence<PreparedSpeech> = sequence {
        val sentences = words.groupBy { it.sentenceId }.values.toList()
        val start = if (requestedWordId == null) 0 else sentences.indexOfFirst { sentence -> sentence.any { it.id == requestedWordId } }
        require(start >= 0) { "The selected word is no longer on this page." }
        var seeking = requestedWordId != null
        for (sentence in sentences.drop(start)) {
            try {
                // Begin with the selected spoken word, retaining both fragments of a joined word.
                // Without alignment, sample zero still starts at the actual selected text.
                val sourceGroups = sourceGrouper.sourceGroups(sentence)
                val selected = if (seeking && fromSelectedWord) sourceGroups.dropWhile { group -> group.none { it.id == requestedWordId } } else sourceGroups
                for (group in chunkGroups(selected)) {
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

    private fun chunkGroups(groups: List<List<SourceWord>>): List<List<List<SourceWord>>> {
        val chunks = mutableListOf<List<List<SourceWord>>>()
        var chunk = mutableListOf<List<SourceWord>>()
        var size = 0
        for (group in groups) {
            require(group.all { it.text.length <= maxCharacters }) { "A single token exceeds the synthesis limit; it was not truncated." }
            val groupSize = group.sumOf { it.text.length + 1 }
            if (chunk.isNotEmpty() && size + groupSize > maxCharacters) {
                chunks += chunk; chunk = mutableListOf(); size = 0
            }
            chunk += group; size += groupSize
        }
        if (chunk.isNotEmpty()) chunks += chunk
        return chunks
    }

    private fun split(groups: List<List<SourceWord>>): Sequence<PreparedSpeech> = sequence {
        val words = groups.flatten()
        val speech = normalizer.normalize(words)
        if (speech.text.length <= maxCharacters) yield(PreparedSpeech(words, speech))
        else {
            require(groups.size > 1) { "This token exceeds the speech model limit; it was not truncated." }
            val middle = groups.size / 2
            yieldAll(split(groups.take(middle)))
            yieldAll(split(groups.drop(middle)))
        }
    }
}
