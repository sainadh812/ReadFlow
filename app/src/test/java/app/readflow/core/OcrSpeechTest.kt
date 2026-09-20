package app.readflow.core

import org.junit.Assert.*
import org.junit.Test

class OcrSpeechTest {
    private fun page(text: String) = DocumentPipeline().process("ocr-punctuation-fixture", 0,
        ExtractedPage(listOf(TextElement(text, emptyList())), 600f, 800f, "Latin OCR"))
    private fun normalize(text: String) = EnglishNormalizer().normalize(page(text).words)

    @Test fun attachedNumeralIsSpokenAndKeepsItsSourceWord() {
        val page = page("We read about money.4")
        val speech = EnglishNormalizer().normalize(page.words)
        val word = page.words.last()
        assertEquals("We read about money. four", speech.text)
        assertEquals(listOf("MONEY", "FOUR"), speech.tokens.filter { word.id in it.sourceIds }.map { it.text })
        assertEquals(page.words.map { it.id }, speech.sourceIds)
        assertEquals("money.4", page.original.substring(word.sourceStart, word.sourceEnd))
        assertEquals("money.4", page.reading.substring(word.start, word.end))
    }
    @Test fun quotedAndBracketedAttachedNumeralsRemainLiteral() {
        val examples = mapOf(
            "Words.12" to "Words. twelve", "Words!4" to "Words! four", "Words?4." to "Words? four.",
            "\u201cWords.\u201d4" to "\"Words.\" four", "Words.[4]" to "Words. (four)",
            "Words.(12)" to "Words. (twelve)", "Readers' words.4" to "Readers' words. four",
        )
        examples.forEach { (input, expected) -> assertEquals(input, expected, normalize(input).text) }
        assertEquals("Words. superscript four", normalize("Words.\u2074").text)
    }
    @Test fun decimalAndUnitPronunciationsDoNotBecomeReferenceMarkers() {
        assertEquals("Value three point one four and two point five kilograms.", normalize("Value 3.14 and 2.5kg.").text)
        assertEquals("Loss minus zero point five percent.", normalize("Loss -0.5%.").text)
    }
    @Test fun ambiguousExpressionsRemainRejected() {
        for (input in listOf("Value 1,2.", "x = y", "x\u00b2", "v1.2", "Words.4+5", "Words.999999999999999999999")) {
            assertThrows(input, IllegalArgumentException::class.java) { normalize(input) }
        }
    }
    @Test fun verticalBarHasLiteralSpeechWithoutGuessingAnOcrCorrection() {
        val page = page("You read faster than | do.")
        val original = page.copy()
        val speech = EnglishNormalizer().normalize(page.words)
        val bar = page.words.first { it.text == "|" }
        assertEquals("You read faster than vertical bar do.", speech.text)
        assertEquals(listOf("VERTICAL", "BAR"), speech.tokens.filter { bar.id in it.sourceIds }.map { it.text })
        assertEquals(original, page)
        assertEquals("vertical bar x vertical bar", normalize("|x|").text)
    }
    @Test fun sentencePreparationHandlesBothPatternsAndKeepsEveryMapping() {
        val page = page("You read faster than | do. In 2006 our 50 volunteers read the results-a report about money.4")
        val chunks = SpeechPlanner().prepare(page.words).toList()
        assertEquals(page.words, chunks.flatMap { it.words })
        assertEquals("You read faster than vertical bar do. In two thousand six our fifty volunteers read the results-a report about money. four",
            chunks.joinToString(" ") { it.speech.text })
        assertTrue(page.words.all { word -> chunks.any { chunk -> chunk.speech.tokens.any { word.id in it.sourceIds } } })
    }
    @Test fun repeatedAttachedTokensKeepIndependentJumpTargets() {
        val page = page("Read money.4 once. Read money.4 again.")
        val repeated = page.words.filter { it.text == "money.4" }
        assertEquals(2, repeated.size)
        assertNotEquals(repeated[0].id, repeated[1].id)
        val speech = SpeechPlanner().prepare(page.words, repeated[1].id).first().speech
        assertFalse(repeated[0].id in speech.sourceIds)
        assertEquals(listOf("MONEY", "FOUR"), speech.tokens.filter { repeated[1].id in it.sourceIds }.map { it.text })
    }
}
