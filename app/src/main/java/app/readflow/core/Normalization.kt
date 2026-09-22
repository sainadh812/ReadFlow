package app.readflow.core

import java.util.Locale

class NormalizationException(val sourceIds: List<String>, val sourceText: String, val expansion: String, reason: String) :
    IllegalArgumentException(reason)

class EnglishNormalizer : TextNormalizer {
    companion object { const val VERSION = "english-4" }
    private val small = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    fun number(n: Long): String = when {
        n < 0 -> "minus " + number(-n)
        n < 20 -> small[n.toInt()]
        n < 100 -> tens[(n / 10).toInt()] + if (n % 10 > 0) " " + number(n % 10) else ""
        n < 1000 -> number(n / 100) + " hundred" + if (n % 100 > 0) " " + number(n % 100) else ""
        else -> {
            val scale = listOf(1_000_000_000L to "billion", 1_000_000L to "million", 1000L to "thousand").first { n >= it.first }
            number(n / scale.first) + " " + scale.second + if (n % scale.first > 0) " " + number(n % scale.first) else ""
        }
    }
    override fun normalize(words: List<SourceWord>): SpeechText {
        val tokens = mutableListOf<SpokenToken>()
        val spoken = mutableListOf<String>()
        for (group in sourceGroups(words)) {
            val word = group.first()
            var raw = typography(word.text)
            val sourceText = group.joinToString(" ") { it.text }
            val ids = group.map { it.id }
            if (group.size == 2) raw = raw.dropLast(1) + typography(group[1].text)
            val expansion = LatinPronunciation.forSpeech(raw).trim().split(Regex("\\s+")).joinToString(" ", transform = ::expandToken)
            // The same expansion feeds synthesis and alignment, never a second guessed transcript.
            if (expansion.any { it.isDigit() || (it.isLetter() && it !in 'A'..'Z' && it !in 'a'..'z') })
                throw NormalizationException(ids.toList(), sourceText, expansion, "This sentence needs an explicit English pronunciation.")
            if (!expansion.all { it.isLetter() || it.isWhitespace() || it in "'.,!?;:()\"-" })
                throw NormalizationException(ids.toList(), sourceText, expansion, "This sentence contains an unsupported symbol or equation.")
            spoken += expansion
            Regex("[A-Za-z]+(?:'[A-Za-z]+)*").findAll(expansion).forEach {
                tokens += SpokenToken(it.value.uppercase(Locale.US), ids.toList())
            }
        }
        require(tokens.isNotEmpty()) { "No spoken words in this sentence." }
        return SpeechText(spoken.filter { it.isNotEmpty() }.joinToString(" ").replace(Regex("\\s+([,.;:!?)])"), "$1"), tokens, words.map { it.id })
    }

    // Keep the same pair consumption for normalization, source-word selection and chunking.
    // This only examines typography/geometry; unsupported earlier words are not expanded.
    internal fun sourceGroups(words: List<SourceWord>): List<List<SourceWord>> {
        val groups = mutableListOf<List<SourceWord>>()
        var i = 0
        while (i < words.size) {
            val word = words[i]
            val next = words.getOrNull(i + 1)
            val joins = typography(word.text).endsWith("-") && next != null &&
                next.paragraphId == word.paragraphId && next.text.firstOrNull()?.isLowerCase() == true &&
                next.sourceStart > word.sourceEnd && word.boxes.isNotEmpty() && next.boxes.isNotEmpty() &&
                next.boxes.first().top > word.boxes.first().bottom - 2
            val count = if (joins) 2 else 1
            groups += words.subList(i, i + count)
            i += count
        }
        return groups
    }

    private fun typography(text: String): String = buildString {
        // Read reference-like markers literally; never assume that a superscript is an exponent or footnote.
        val prepared = Regex("(?<=[.!?\"'\u2019\u201d])([\u2070\u00b9\u00b2\u00b3\u2074-\u2079]+)").replace(text) { match ->
            " superscript " + match.value.map { small["\u2070\u00b9\u00b2\u00b3\u2074\u2075\u2076\u2077\u2078\u2079".indexOf(it)] }.joinToString(" ")
        }
        for (character in prepared) append(when (character) {
            '\u2018', '\u2019' -> "'"
            '\u201c', '\u201d', '\u00ab', '\u00bb' -> "\""
            '\u2010', '\u2011', '\u2013', '\u2014' -> "-"
            '\u2026' -> "..."
            '[' -> "("
            ']' -> ")"
            '\u00ad', '\ufeff' -> ""
            '\uf0c1' -> "" // Sphinx heading-link icon retained in older saved articles.
            '\ufb00' -> "ff"
            '\ufb01' -> "fi"
            '\ufb02' -> "fl"
            '\ufb03' -> "ffi"
            '\ufb04' -> "ffl"
            '\u200b' -> " "
            '\u2022', '\u2023', '\u25e6' -> ","
            '&' -> " and "
            '/' -> " slash "
            '|' -> " vertical bar "
            '_' -> " underscore "
            '@' -> " at "
            '\u00a9' -> " copyright "
            '\u00ae' -> " registered trademark "
            '\u2122' -> " trademark "
            else -> if (character.isWhitespace()) " " else character.toString()
        })
    }

    private fun expandToken(raw: String): String {
        val prefix = raw.takeWhile { it in "\"'(" }
        val rest = raw.drop(prefix.length)
        val suffix = rest.takeLastWhile { it in ",.;:!?\"')" }
        val bare = rest.dropLast(suffix.length)
        val number = Regex("^([£$€₹]?)([-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+))(?:\\.(\\d+))?(%|kg|km|cm|mm|mg|mL|ml|Hz)?$").matchEntire(bare)
        if (number != null && number.groupValues[2].replace(",", "").toLongOrNull()?.let { it in -999_999_999_999L..999_999_999_999L } == true) {
            val value = number.groupValues[2].replace(",", "").toLong()
            val integer = if (value == 0L && number.groupValues[2].startsWith('-')) "minus zero" else number(value)
            val decimal = number.groupValues[3].let { if (it.isEmpty()) "" else " point " + it.map { c -> small[c.digitToInt()] }.joinToString(" ") }
            val currency = mapOf("$" to " dollars", "£" to " pounds", "€" to " euros", "₹" to " rupees")[number.groupValues[1]].orEmpty()
            val unit = mapOf("%" to " percent", "kg" to " kilograms", "km" to " kilometers", "cm" to " centimeters", "mm" to " millimeters", "mg" to " milligrams", "ml" to " milliliters", "mL" to " milliliters", "Hz" to " hertz")[number.groupValues[4]].orEmpty()
            return prefix + integer + decimal + currency + unit + suffix
        }
        // OCR may flatten a reference into "word.4". Speak it literally, without dropping it or labelling it a footnote.
        val attachedNumeral = Regex("^([A-Za-z]+(?:['-][A-Za-z]+)*[.!?]+[\"')]*)(\\(?[0-9]+)$").matchEntire(bare)
        if (attachedNumeral != null) {
            return prefix + expandToken(attachedNumeral.groupValues[1]) + " " + expandToken(attachedNumeral.groupValues[2]) + suffix
        }
        val decade = Regex("(\\d{2}|\\d{4})s").matchEntire(bare)?.groupValues?.get(1)?.toInt()
        if (decade != null && decade % 10 == 0 && decade >= 20) {
            val plural = if (decade % 100 == 0) number(decade.toLong()) + "s"
                else (if (decade >= 100) number((decade / 100).toLong()) + " " else "") + number((decade % 100).toLong()).dropLast(1) + "ies"
            return prefix + plural + suffix
        }
        val ordinal = Regex("(\\d+)(st|nd|rd|th)").matchEntire(bare)
        if (ordinal != null) {
            val value = ordinal.groupValues[1].toLongOrNull()
            if (value != null && value <= 999_999_999_999L) {
                val cardinal = number(value)
                val last = cardinal.substringAfterLast(' ')
                val ending = mapOf("one" to "first", "two" to "second", "three" to "third", "five" to "fifth",
                    "eight" to "eighth", "nine" to "ninth", "twelve" to "twelfth")[last]
                    ?: if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
                return prefix + cardinal.dropLast(last.length) + ending + suffix
            }
        }
        if (Regex("(?:[A-Za-z]+-\\d[\\d,]*s?|\\d[\\d,]*-[A-Za-z]+(?:-[A-Za-z]+)*)").matches(bare)) {
            return prefix + bare.split('-').joinToString("-", transform = ::expandToken) + suffix
        }
        // Spell numeric parts of identifiers/compounds literally, without interpreting an expression.
        if (bare.any { it.isDigit() } && bare.any { it.isLetter() } && Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*").matches(bare)) {
            val expanded = Regex("[A-Za-z]+|[0-9]+").findAll(bare).map { part ->
                if (part.value.first().isDigit()) part.value.toLongOrNull()?.takeIf { it <= 999_999_999_999L }?.let(::number) ?: part.value
                else part.value
            }.joinToString(" ")
            return prefix + expanded + suffix
        }
        return mapOf("Dr." to "Doctor", "Mr." to "Mister", "Mrs." to "Missus", "Ms." to "Miz", "e.g." to "for example", "i.e." to "that is",
            "kg" to "kilograms", "km" to "kilometers", "cm" to "centimeters", "mg" to "milligrams")[raw] ?: raw
    }
}

fun chunkWords(words: List<SourceWord>, maxCharacters: Int = 220): List<List<SourceWord>> {
    val result = mutableListOf<List<SourceWord>>()
    var chunk = mutableListOf<SourceWord>()
    var size = 0
    for (word in words) {
        require(word.text.length <= maxCharacters) { "A single token exceeds the synthesis limit; it was not truncated." }
        if (chunk.isNotEmpty() && (chunk.last().sentenceId != word.sentenceId || size + word.text.length + 1 > maxCharacters)) {
            result += chunk; chunk = mutableListOf(); size = 0
        }
        chunk += word; size += word.text.length + 1
    }
    if (chunk.isNotEmpty()) result += chunk
    return result
}

fun cacheKey(documentId: String, speech: SpeechText, model: String, voice: String, settings: String, alignment: String,
    normalizationVersion: String = EnglishNormalizer.VERSION) =
    sha256(listOf(documentId, PIPELINE_VERSION, normalizationVersion, speech.text, speech.sourceIds.joinToString(","), model, voice, settings, alignment).joinToString("\u0000").toByteArray())

fun activeWord(timings: List<WordTiming>, positionMs: Long, sampleRate: Int): WordTiming? {
    val sample = positionMs.coerceAtLeast(0) * sampleRate / 1000
    return timings.firstOrNull { sample >= it.startSample && sample < it.endSample }
}
fun activeWordIds(timings: List<WordTiming>, positionMs: Long, sampleRate: Int): Set<String> {
    val sample = positionMs.coerceAtLeast(0) * sampleRate / 1000
    return timings.filter { sample >= it.startSample && sample < it.endSample }.mapTo(linkedSetOf()) { it.wordId }
}
