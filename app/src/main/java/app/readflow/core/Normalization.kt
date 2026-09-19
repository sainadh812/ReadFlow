package app.readflow.core

import java.util.Locale

class EnglishNormalizer : TextNormalizer {
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
        var i = 0
        while (i < words.size) {
            val word = words[i]
            var raw = word.text.replace('“', '"').replace('”', '"').replace('–', '-').replace('—', '-').replace("…", "...")
            val ids = mutableListOf(word.id)
            val next = words.getOrNull(i + 1)
            if (raw.endsWith("-") && next != null && next.paragraphId == word.paragraphId && next.text.firstOrNull()?.isLowerCase() == true && next.sourceStart > word.sourceEnd &&
                word.boxes.isNotEmpty() && next.boxes.isNotEmpty() && next.boxes.first().top > word.boxes.first().bottom - 2) {
                raw = raw.dropLast(1) + next.text; ids += next.id; i++
            }
            val suffix = raw.takeLastWhile { it in ",.;:!?" }
            val bare = raw.dropLast(suffix.length)
            val number = Regex("^([£$€₹]?)([-+]?\\d[\\d,]*)(?:\\.(\\d+))?(%|kg|km|cm|mm|mg|mL|ml|Hz)?$").matchEntire(bare)
            val expansion = if (number != null && number.groupValues[2].replace(",", "").toLongOrNull()?.let { it in -999_999_999_999L..999_999_999_999L } == true) {
                val integer = number(number.groupValues[2].replace(",", "").toLong())
                val decimal = number.groupValues[3].let { if (it.isEmpty()) "" else " point " + it.map { c -> small[c.digitToInt()] }.joinToString(" ") }
                val currency = mapOf("$" to " dollars", "£" to " pounds", "€" to " euros", "₹" to " rupees")[number.groupValues[1]].orEmpty()
                val unit = mapOf("%" to " percent", "kg" to " kilograms", "km" to " kilometers", "cm" to " centimeters", "mm" to " millimeters", "mg" to " milligrams", "ml" to " milliliters", "mL" to " milliliters", "Hz" to " hertz")[number.groupValues[4]].orEmpty()
                integer + decimal + currency + unit + suffix
            } else mapOf("Dr." to "Doctor", "Mr." to "Mister", "Mrs." to "Missus", "Ms." to "Miz", "e.g." to "for example", "i.e." to "that is",
                "kg" to "kilograms", "km" to "kilometers", "cm" to "centimeters", "mg" to "milligrams")[raw] ?: raw
            spoken += expansion
            val spokenWords = Regex("[A-Za-z]+(?:['’][A-Za-z]+)*").findAll(expansion)
            spokenWords.forEach { tokens += SpokenToken(it.value.replace('’', '\'').uppercase(Locale.US), ids.toList()) }
            // Fail closed for unsupported scripts/symbols rather than align a different transcript.
            require(!expansion.any { it.isDigit() || (it.isLetter() && it !in 'A'..'Z' && it !in 'a'..'z') }) { "This sentence contains text that needs an explicit English pronunciation." }
            require(expansion.all { it.isLetter() || it.isWhitespace() || it in "'’.,!?;:()\"-" }) { "Unsupported symbol or equation: skip this sentence or page." }
            i++
        }
        require(tokens.isNotEmpty()) { "No spoken words in this selection" }
        return SpeechText(spoken.joinToString(" "), tokens, words.map { it.id })
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

fun cacheKey(documentId: String, speech: SpeechText, model: String, voice: String, settings: String, alignment: String) =
    sha256(listOf(documentId, PIPELINE_VERSION, speech.text, speech.sourceIds.joinToString(","), model, voice, settings, alignment).joinToString("\u0000").toByteArray())

fun activeWord(timings: List<WordTiming>, positionMs: Long, sampleRate: Int): WordTiming? {
    val sample = positionMs.coerceAtLeast(0) * sampleRate / 1000
    return timings.firstOrNull { sample >= it.startSample && sample < it.endSample }
}
fun activeWordIds(timings: List<WordTiming>, positionMs: Long, sampleRate: Int): Set<String> {
    val sample = positionMs.coerceAtLeast(0) * sampleRate / 1000
    return timings.filter { sample >= it.startSample && sample < it.endSample }.mapTo(linkedSetOf()) { it.wordId }
}
