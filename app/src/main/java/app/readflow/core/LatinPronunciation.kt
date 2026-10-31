package app.readflow.core

import java.text.Normalizer
import java.util.Locale

/** English speech approximations only. Never replace the document's spelling or source geometry. */
internal object LatinPronunciation {
    private val words = Regex("\\p{L}[\\p{L}\\p{M}]*")
    // Whole-name exceptions precede accent folding. These spellings also feed the English aligner.
    private val names = mapOf("milanković" to "milankovitch", "milankovic" to "milankovitch")
    private val letters = mapOf(
        'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'ł' to "l", 'đ' to "d", 'ð' to "th",
        'þ' to "th", 'ß' to "ss", 'ı' to "i", 'ħ' to "h", 'ŋ' to "ng", 'ŧ' to "t",
    )
    // Latin accents, including decomposed Vietnamese forms. Do not erase arbitrary marks such as
    // vector arrows, strike-throughs, orphan accents, or marks belonging to another script.
    private val accents = setOf(
        '\u0300', '\u0301', '\u0302', '\u0303', '\u0304', '\u0306', '\u0307', '\u0308',
        '\u0309', '\u030a', '\u030b', '\u030c', '\u030f', '\u0311', '\u031b', '\u0323',
        '\u0324', '\u0325', '\u0326', '\u0327', '\u0328', '\u032d', '\u032e', '\u0330', '\u0331',
    )

    fun forSpeech(text: String): String = words.replace(Normalizer.normalize(text, Normalizer.Form.NFC)) { match ->
        val word = match.value
        val name = names[word.lowercase(Locale.ROOT)]
        if (name != null) when {
            word.all { !it.isLetter() || it.isUpperCase() } -> name.uppercase(Locale.ROOT)
            word.first().isUpperCase() -> name.replaceFirstChar { it.uppercaseChar() }
            else -> name
        } else foldLatin(word)
    }

    private fun foldLatin(word: String): String = buildString {
        var latinBase = false
        for (character in Normalizer.normalize(word, Normalizer.Form.NFD)) {
            if (character in accents && latinBase) continue
            val replacement = letters[character.lowercaseChar()]
            when {
                replacement != null -> {
                    append(if (character.isUpperCase()) replacement.uppercase(Locale.ROOT) else replacement)
                    latinBase = true
                }
                character in 'A'..'Z' || character in 'a'..'z' -> { append(character); latinBase = true }
                else -> { append(character); latinBase = false }
            }
        }
    }
}
