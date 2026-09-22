package app.readflow.core

import org.junit.Assert.*
import org.junit.Test

class NumericSpeechTest {
    private fun page(text: String) = DocumentPipeline().process("numeric-fixture", 0,
        ExtractedPage(listOf(TextElement(text, emptyList())), 600f, 800f, "fixture"))
    private fun speech(text: String) = EnglishNormalizer().normalize(page(text).words)

    @Test fun commaReferenceIsReadWithoutDiscardingItsNumber() {
        val source = page("Adjusted for inflation,16")
        val result = EnglishNormalizer().normalize(source.words)
        assertEquals("Adjusted for inflation, sixteen", result.text)
        assertEquals(listOf("INFLATION", "SIXTEEN"), result.tokens.filter { source.words.last().id in it.sourceIds }.map { it.text })
        assertEquals("inflation,16", source.reading.substring(source.words.last().start, source.words.last().end))
        assertEquals("Koppen, sixteen", speech("Köppen,16").text)
        assertEquals("inflation, (sixteen)", speech("inflation,[16]").text)
        assertEquals("inflation, superscript one six", speech("inflation,¹⁶").text)
    }

    @Test fun currencyMagnitudePrecedesTheCurrencyAndKeepsBothSourceIds() {
        val source = page("Worth $1 million, or $9.3 million adjusted for inflation,16")
        val result = EnglishNormalizer().normalize(source.words)
        assertEquals("Worth one million dollars, or nine point three million dollars adjusted for inflation, sixteen", result.text)
        assertEquals(source.words.map { it.id }, result.sourceIds)
        val ids = source.words.subList(1, 3).map { it.id }
        assertEquals(listOf("ONE", "MILLION", "DOLLARS"), result.tokens.filter { it.sourceIds == ids }.map { it.text })
        assertEquals("one dollar two dollars one pound one euro one rupee", speech("$1 $2 £1 €1 ₹1").text)
        assertEquals("one point zero zero dollar", speech("$1.00").text)
        assertEquals("\"nine point three million dollars,\"", speech("“$9.3 million,”").text)
    }

    @Test fun ageRangesAndTypographyVariantsReadAsRanges() {
        for (dash in listOf("-", "–", "—", "‑")) {
            assertEquals("he is twenty to twenty four years old", speech("he is 20${dash}24 years old").text)
        }
        assertEquals("one point five to two point five kilograms", speech("1.5-2.5 kg").text)
        assertEquals("twenty four dash twenty", speech("24-20").text)
    }

    @Test fun timelineNamesAndStorageValuesUseAPauseInsteadOfARange() {
        val examples = mapOf(
            "1999-Apple's iMac with 6 gigs" to "nineteen ninety nine: Apple's iMac with six gigs",
            "2003-120 gigs" to "two thousand three: one hundred twenty gigs",
            "2006–250 gigs" to "two thousand six: two hundred fifty gigs",
            "2006—250 gigabytes" to "two thousand six: two hundred fifty gigabytes")
        examples.forEach { (raw, expected) -> assertEquals(raw, expected, speech(raw).text) }
        // Without the storage context, preserve the ambiguous dash rather than invent a timeline.
        assertEquals("two thousand three dash one hundred twenty", speech("2003-120").text)
        assertEquals("one thousand nine hundred ninety nine to two thousand three", speech("1999-2003").text)
    }

    @Test fun signedNumbersCompoundsAndExistingNumericFormatsAreUnchanged() {
        assertEquals("minus twenty four COVID-nineteen twenty-year-old", speech("-24 COVID-19 20-year-old").text)
        assertEquals("one thousand two hundred thirty four point five six dollars", speech("$1,234.56").text)
        assertEquals("minus zero point five percent three point one four two point five kilograms", speech("-0.5% 3.14 2.5kg").text)
        assertEquals("Milankovitch's", speech("Milankovićʼs").text)
        for (raw in listOf("1,2", "v1.2", "word,16+2", "word,9999999999999999", "20-24=4", "1000000000000-2000000000000")) {
            assertThrows(raw, NormalizationException::class.java) { speech(raw) }
        }
    }

    @Test fun selectionAndChunkingRetainIndivisiblePhrases() {
        val source = page("Value $9.3 million today and 2003-120 gigs later.")
        val chunks = SpeechPlanner(maxCharacters = 50).prepare(source.words).toList()
        assertEquals(source.words, chunks.flatMap { it.words })
        assertTrue(chunks.all { it.speech.text.length <= 50 })
        assertEquals("Value nine point three million dollars today and two thousand three: one hundred twenty gigs later.", chunks.joinToString(" ") { it.speech.text })
        for (index in listOf(2)) {
            val selected = SpeechPlanner().prepare(source.words, source.words[index].id, fromSelectedWord = true).first()
            assertEquals(source.words[index - 1], selected.words.first())
            assertTrue(source.words[index].id in selected.speech.tokens.first().sourceIds)
        }
        val unitSelection = SpeechPlanner().prepare(source.words, source.words[6].id, fromSelectedWord = true).first()
        assertEquals("gigs later.", unitSelection.speech.text)
        val unitToken = chunks.flatMap { it.speech.tokens }.single { it.text == "GIGS" }
        assertEquals(listOf(source.words[6].id), unitToken.sourceIds)
    }

    @Test fun repeatedReferencesRemainSeparateAndNewRulesInvalidateCachedAudio() {
        val source = page("inflation,16 then inflation,16")
        val result = EnglishNormalizer().normalize(source.words)
        assertNotEquals(result.tokens.first().sourceIds, result.tokens.last().sourceIds)
        assertNotEquals(cacheKey("doc", result, "model", "voice", "settings", "align", "english-4"),
            cacheKey("doc", result, "model", "voice", "settings", "align"))
    }
}
