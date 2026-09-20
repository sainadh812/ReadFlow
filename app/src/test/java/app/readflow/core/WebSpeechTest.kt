package app.readflow.core

import org.junit.Assert.*
import org.junit.Test

class WebSpeechTest {
    private fun page(text: String) = DocumentPipeline().process("web-fixture", 0,
        ExtractedPage(listOf(TextElement(text, emptyList())), 0f, 0f, "Saved article"))
    private fun normalize(text: String) = EnglishNormalizer().normalize(page(text).words)

    @Test fun ampersandsKeepTheirOriginalWordMapping() {
        val page = page("Research & development at AT&T.")
        val speech = EnglishNormalizer().normalize(page.words)
        assertEquals("Research and development at AT and T.", speech.text)
        assertEquals(listOf(page.words[1].id), speech.tokens[1].sourceIds)
        assertEquals(listOf("AT", "AND", "T"), speech.tokens.filter { page.words.last().id in it.sourceIds }.map { it.text })
        assertEquals("Research & development at AT&T.", page.reading)
    }
    @Test fun smartQuotesBracketsAndQuotedNumbersAreReadable() {
        val speech = normalize("\u2018Read\u2019 [this] \u201c12kg\u201d (\u00a320.50).")
        assertEquals("'Read' (this) \"twelve kilograms\" (twenty point five zero pounds).", speech.text)
        assertEquals("READ", speech.tokens.first().text)
    }
    @Test fun webSpacingAndSoftHyphensDoNotChangeSourceOffsets() {
        val page = page("Read\u00a0this co\u00adoperate\u202fnow.")
        val speech = EnglishNormalizer().normalize(page.words)
        assertEquals("Read this cooperate now.", speech.text)
        page.words.forEach { assertEquals(it.text, page.reading.substring(it.start, it.end)) }
        assertTrue(speech.tokens.all { token -> token.sourceIds.all { id -> page.words.any { it.id == id } } })
    }
    @Test fun commonWebSymbolsHaveExplicitPronunciations() {
        assertEquals("copyright two thousand twenty six ReadFlow trademark registered trademark.", normalize("\u00a9 2026 ReadFlow\u2122\u00ae.").text)
        assertEquals("Read and slash or listen twenty four slash seven.", normalize("Read and/or listen 24/7.").text)
        assertEquals(", A list item.", normalize("\u2022 A list item.").text)
    }
    @Test fun actualEquationsUnsupportedScriptsAndEmojiStillFailClosed() {
        for (text in listOf("x = y", "a + b", "x \u2264 y", "x\u00b2", "\uD83D\uDE00 hello", "\u4f60\u597d", "Value 1,2.")) {
            assertThrows("Must not invent speech for $text", IllegalArgumentException::class.java) { normalize(text) }
        }
    }
    @Test fun technicalIdentifiersAndLegacyHeadingIconsHaveLiteralSpeech() {
        val page = page("Overview\uf0c1 AXI4 clk_i fifo_v3 4-KiB.")
        val speech = EnglishNormalizer().normalize(page.words)
        assertEquals("Overview AXI four clk underscore i fifo underscore v three four-KiB.", speech.text)
        assertEquals(3, speech.tokens.count { page.words[2].id in it.sourceIds })
    }
    @Test fun bookTypographyDecadesAndLigaturesRemainReadable() {
        assertEquals("In his twenties and in the nineteen nineties, office reading.", normalize("In his 20s and in the 1990s, o\ufb03ce reading.").text)
        assertEquals("An eighteen thousand-square foot space on the twenty first floor.", normalize("An 18,000-square foot space on the 21st floor.").text)
        assertEquals("Words. superscript one two", normalize("Words.\u00b9\u00b2").text)
    }
}
