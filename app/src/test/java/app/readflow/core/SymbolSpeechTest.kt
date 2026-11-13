package app.readflow.core

import org.junit.Assert.*
import org.junit.Test

class SymbolSpeechTest {
    private fun page(text: String) = DocumentPipeline().process("symbol-fixture", 0,
        ExtractedPage(listOf(TextElement(text, emptyList())), 600f, 800f, "fixture"))
    private fun speech(text: String) = EnglishNormalizer().normalize(page(text).words)

    @Test fun reportedSymbolSuffixesHaveLiteralSpeechAndUnchangedSourceText() {
        for ((raw, expected) in mapOf(
            "he is good boy*" to "he is good boy asterisk",
            "he is a good boy₹%&" to "he is a good boy rupee percent and",
        )) {
            val source = page(raw)
            val before = source.copy(words = source.words.toList())
            val prepared = SpeechPlanner().prepare(source.words).single()
            assertEquals(expected, prepared.speech.text)
            assertEquals(before, source)
            assertEquals(raw, source.original)
            assertEquals(source.words, prepared.words)
            assertEquals(source.words.map { it.id }, prepared.speech.sourceIds)
            val last = source.words.last()
            assertTrue(prepared.speech.tokens.takeLast(if (raw.endsWith('*')) 2 else 4).all { it.sourceIds == listOf(last.id) })
            source.words.forEach { assertEquals(it.text, source.original.substring(it.sourceStart, it.sourceEnd)) }
        }
    }

    @Test fun spacedMoneyAndPercentagesKeepEveryOwnerAndSelectionTarget() {
        val source = page("Paid ₹ 50 and earned 20 %.")
        val result = EnglishNormalizer().normalize(source.words)
        assertEquals("Paid fifty rupees and earned twenty percent.", result.text)
        assertEquals(source.words.map { it.id }, result.sourceIds)
        assertEquals(listOf("FIFTY", "RUPEES"), result.tokens.filter { source.words[1].id in it.sourceIds }.map { it.text })
        assertTrue(result.tokens.filter { source.words[1].id in it.sourceIds }.all { it.sourceIds == source.words.subList(1, 3).map { word -> word.id } })
        for (index in listOf(1, 2)) {
            val selected = SpeechPlanner().prepare(source.words, source.words[index].id, true).single()
            assertEquals("fifty rupees and earned twenty percent.", selected.speech.text)
            assertTrue(source.words[index].id in selected.speech.tokens.first().sourceIds)
        }
        for (index in listOf(5, 6)) {
            val selected = SpeechPlanner().prepare(source.words, source.words[index].id, true).single()
            assertEquals("twenty percent.", selected.speech.text)
            assertTrue(source.words[index].id in selected.speech.tokens.first().sourceIds)
        }
    }

    @Test fun spacedCurrencyScalesAreIndivisibleAndPreservePunctuation() {
        assertEquals("\"nine point three million dollars,\"", speech("“$ 9.3 million,”").text)
        assertEquals("one rupee one point zero zero dollar minus zero point five percent.", speech("₹ 1 $ 1.00 -0.5 %.").text)
        val source = page("Value ₹ 9.3 million today.")
        val owners = source.words.subList(1, 4).map { it.id }
        val result = EnglishNormalizer().normalize(source.words)
        assertEquals(listOf("NINE", "POINT", "THREE", "MILLION", "RUPEES"), result.tokens.filter { it.sourceIds == owners }.map { it.text })
        val selected = SpeechPlanner().prepare(source.words, source.words[3].id, true).single()
        assertEquals("nine point three million rupees today.", selected.speech.text)
        assertEquals(owners, selected.speech.tokens.first().sourceIds)
    }

    @Test fun commonOperatorsAreSpokenWithoutEvaluatingExpressions() {
        for ((raw, expected) in mapOf(
            "x = y" to "x equals y",
            "a + b" to "a plus b",
            "x ≤ y" to "x less than or equal to y",
            "5*3=15" to "five times three equals fifteen",
            "20-24=4" to "twenty minus twenty four equals four",
            "2+-3=-1" to "two plus minus three equals minus one",
            "6÷2≠4" to "six divided by two not equal to four",
            "C++ and C#" to "C plus plus and C sharp",
            "Milanković=2" to "Milankovitch equals two",
            "word,16+2" to "word, sixteen plus two",
        )) assertEquals(raw, expected, speech(raw).text)
        // A hyphen remains a range when it is not part of an explicit expression.
        assertEquals("twenty to twenty four", speech("20-24").text)
    }

    @Test fun punctuationOnlySentencesRemainPlayableAndKeepTheirOwners() {
        for ((raw, expected) in mapOf("*" to "asterisk", "..." to "period period period", "---" to "dash dash dash", "₹%" to "rupee percent")) {
            val source = page(raw)
            val result = SpeechPlanner().prepare(source.words).toList()
            assertEquals(expected, result.joinToString(" ") { it.speech.text })
            assertEquals(source.words, result.flatMap { it.words })
            assertTrue(result.all { it.speech.tokens.isNotEmpty() })
            assertTrue(result.flatMap { it.speech.tokens }.all { it.sourceIds.isNotEmpty() })
        }
    }

    @Test fun selectedPunctuationSentenceDoesNotPreventFollowingProse() {
        val source = page("***. Read this next.")
        val chunks = SpeechPlanner().prepare(source.words, source.words.first().id, true).toList()
        assertEquals(source.words, chunks.flatMap { it.words })
        assertTrue(chunks.first().speech.text.startsWith("asterisk"))
        assertEquals("Read this next.", chunks.last().speech.text)
    }

    @Test fun repeatedSymbolWordsKeepIndependentIdsAndExpansionsStayBounded() {
        val source = page((1..30).joinToString(" ") { "boy₹%&" })
        val chunks = SpeechPlanner(maxCharacters = 60).prepare(source.words).toList()
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.speech.text.length <= 60 })
        assertEquals(source.words, chunks.flatMap { it.words })
        assertEquals(source.words.map { it.id }, chunks.flatMap { it.speech.sourceIds })
        for (word in source.words) {
            assertEquals(listOf("BOY", "RUPEE", "PERCENT", "AND"), chunks.flatMap { it.speech.tokens }.filter { word.id in it.sourceIds }.map { it.text })
        }
    }

    @Test fun oversizedSingleExpansionFailsExplicitlyInsteadOfDroppingSymbols() {
        val source = page("*".repeat(40))
        val failure = assertThrows(SpeechPreparationException::class.java) { SpeechPlanner().prepare(source.words).toList() }
        assertEquals(source.words, failure.sentence)
        assertTrue(failure.message!!.contains("not truncated"))
    }

    @Test fun unsupportedCharactersAreNotRemovedAlongsideSupportedSymbols() {
        for (raw in listOf("boy*📖", "x²=2", "café₹東京", "1,2%")) {
            val source = page(raw)
            val failure = assertThrows(NormalizationException::class.java) { EnglishNormalizer().normalize(source.words) }
            assertEquals(source.words.map { it.id }, failure.sourceIds)
            assertEquals(raw, failure.sourceText)
        }
    }

    @Test fun symbolRulesInvalidatePreviouslyCachedEnglishSixAudio() {
        val result = speech("An unchanged sentence.")
        assertNotEquals(cacheKey("doc", result, "model", "voice", "settings", "align", "english-6"),
            cacheKey("doc", result, "model", "voice", "settings", "align"))
    }
}
