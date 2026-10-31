package app.readflow.core

import org.junit.Assert.*
import org.junit.Test

class AccentedSpeechTest {
    private fun page(text: String) = DocumentPipeline().process("accented-speech", 0,
        ExtractedPage(listOf(TextElement(text, emptyList())), 600f, 800f, "Latin OCR"))

    private fun normalize(text: String) = EnglishNormalizer().normalize(page(text).words)

    @Test fun photographedNamesUseSpeakableAliasesAndRetainOriginalHighlights() {
        val text = "Milanković’s theory initially assumed that a tilt of the Earth’s hemispheres caused ravenous winters cold enough to turn the planet into ice. " +
            "But a Russian meteorologist named Wladimir Köppen dug deeper into Milanković’s work and discovered a fascinating nuance. " +
            "Moderately cool summers, not cold winters, were the icy culprit."
        val original = DocumentPipeline().process("photographed-names", 0, ExtractedPage(
            text.split(' ').mapIndexed { index, word ->
                val row = index / 10
                val column = index % 10
                TextElement(word, listOf(Box(column * 50f, row * 30f, column * 50f + 45, row * 30f + 20)), 0, row)
            }, 600f, 800f, "Latin OCR"))
        val before = original.copy(words = original.words.map { it.copy(boxes = it.boxes.toList()) })

        val prepared = SpeechPlanner().prepare(original.words).toList()
        val speech = prepared.joinToString(" ") { it.speech.text }
        assertEquals(text.replace("Milanković", "Milankovitch").replace("Köppen", "Koppen").replace('’', '\''), speech)
        assertEquals(before, original)
        assertEquals(original.words, prepared.flatMap { it.words })
        assertEquals(original.words.map { it.id }, prepared.flatMap { it.speech.sourceIds })

        val names = original.words.filter { it.text == "Milanković’s" || it.text == "Köppen" }
        assertEquals(3, names.size)
        assertEquals(3, names.map { it.id }.distinct().size)
        names.forEach { source ->
            val spoken = prepared.flatMap { it.speech.tokens }.single { source.id in it.sourceIds }
            assertEquals(if (source.text == "Köppen") "KOPPEN" else "MILANKOVITCH'S", spoken.text)
            assertEquals(listOf(source.id), spoken.sourceIds)
            assertEquals(source.text, original.original.substring(source.sourceStart, source.sourceEnd))
            assertEquals(source.text, original.reading.substring(source.start, source.end))
            assertTrue(source.boxes.isNotEmpty())
        }
    }

    @Test fun composedAndDecomposedNamesHaveIdenticalSpeech() {
        val composed = normalize("Milanković’s and Köppen’s café.")
        val decomposedPage = page("Milankovic\u0301’s and Ko\u0308ppen’s cafe\u0301.")
        val decomposed = EnglishNormalizer().normalize(decomposedPage.words)
        assertEquals("Milankovitch's and Koppen's cafe.", composed.text)
        assertEquals(composed.text, decomposed.text)
        assertEquals(composed.tokens.map { it.text }, decomposed.tokens.map { it.text })
        assertEquals(decomposedPage.words.map { it.id }, decomposed.sourceIds)
        decomposedPage.words.forEach { source ->
            assertEquals(source.text, decomposedPage.reading.substring(source.start, source.end))
            assertEquals(source.text, decomposedPage.original.substring(source.sourceStart, source.sourceEnd))
        }
    }

    @Test fun nameAliasesHandleCasePossessivesAndPunctuationWithoutReplacingLongerWords() {
        val examples = mapOf(
            "“Milanković’s,”" to "\"Milankovitch's,\"",
            "MILANKOVIĆ'S" to "MILANKOVITCH'S",
            "milankovic's" to "milankovitch's",
            "Milankovic." to "Milankovitch.",
            "(Köppen’s)" to "(Koppen's)",
            "KÖPPEN" to "KOPPEN",
            "köppen" to "koppen",
            "Milankovićsonian" to "Milankovicsonian",
        )
        examples.forEach { (input, expected) -> assertEquals(input, expected, normalize(input).text) }
    }

    @Test fun otherLatinAccentsProduceAsciiSpeechWithoutDroppingSourceWords() {
        val original = page("François visited São Paulo for a naïve résumé of über café culture.")
        val speech = EnglishNormalizer().normalize(original.words)
        assertEquals("Francois visited Sao Paulo for a naive resume of uber cafe culture.", speech.text)
        assertEquals(original.words.map { it.id }, speech.sourceIds)
        assertEquals(original.words.map { listOf(it.id) }, speech.tokens.map { it.sourceIds })
        assertTrue(speech.tokens.all { token -> token.text.all { it in 'A'..'Z' } })
    }

    @Test fun LatinLettersThatDoNotDecomposeStillHaveSpokenForms() {
        assertEquals("aesop oeuvre Oresund Lodz Dorde thora thorn Strasse.",
            normalize("æsop œuvre Øresund Łódź Đorđe ðora þorn Straße.").text)
        assertEquals("AE OE O L D TH TH SS ae oe o l d th th ss",
            normalize("Æ Œ Ø Ł Đ Ð Þ ẞ æ œ ø ł đ ð þ ß").text)
    }

    @Test fun accentedWordsKeepExistingNumericExpansionsAndTheirSourceMapping() {
        val examples = mapOf(
            "Milanković’s.4" to "Milankovitch's. four",
            "Köppen.[12]" to "Koppen. (twelve)",
            "café3" to "cafe three",
            "café-12" to "cafe-twelve",
            "résumé.⁴" to "resume. superscript four",
        )
        examples.forEach { (input, expected) ->
            val original = page(input)
            val speech = EnglishNormalizer().normalize(original.words)
            assertEquals(input, expected, speech.text)
            assertTrue(input, speech.tokens.all { it.sourceIds == listOf(original.words.single().id) })
        }
    }

    @Test fun unsupportedScriptsSymbolsAndOrphanAccentsAreNotSilentlyRemoved() {
        val examples = listOf("café東京", "caféα", "caf\u0435\u0301", "\u0301café", "3\u0301", ".\u0301", "x\u20d7", "Milanković=2", "café²", "café📖")
        examples.forEach { input ->
            val original = page(input)
            val error = assertThrows(input, NormalizationException::class.java) {
                EnglishNormalizer().normalize(original.words)
            }
            assertEquals(input, error.sourceText)
            assertEquals(original.words.map { it.id }, error.sourceIds)
        }
    }

    @Test fun selectedRepeatedNameKeepsItsOwnJumpTarget() {
        val original = page("Milanković’s work informed Köppen. Milanković’s work continues.")
        val names = original.words.filter { it.text == "Milanković’s" }
        val selected = names.last()
        val prepared = SpeechPlanner().prepare(original.words, selected.id, fromSelectedWord = true).toList()
        assertEquals("Milankovitch's work continues.", prepared.single().speech.text)
        assertEquals(original.words.dropWhile { it.id != selected.id }, prepared.single().words)
        assertEquals(listOf(selected.id), prepared.single().speech.tokens.first().sourceIds)
        assertFalse(names.first().id in prepared.single().speech.sourceIds)
    }

    @Test fun selectingSecondLineOfAnAccentedNameRetainsBothHighlightBoxes() {
        val original = DocumentPipeline().process("accented-line-break", 0, ExtractedPage(listOf(
            TextElement("Milanko-", listOf(Box(10f, 10f, 90f, 30f)), 0, 0),
            TextElement("vić’s", listOf(Box(10f, 40f, 50f, 60f)), 0, 1),
            TextElement("work.", listOf(Box(60f, 40f, 100f, 60f)), 0, 1),
        ), 200f, 300f, "Latin OCR"))
        val selected = original.words[1]
        val prepared = SpeechPlanner().prepare(original.words, selected.id, fromSelectedWord = true).single()
        assertEquals("Milankovitch's work.", prepared.speech.text)
        assertEquals(original.words, prepared.words)
        assertEquals(original.words.take(2).map { it.id }, prepared.speech.tokens.first().sourceIds)
        val timings = original.words.take(2).map { WordTiming(it.id, 160, 1600, 1f) }
        assertEquals(original.words.take(2).map { it.id }.toSet(), activeWordIds(timings, 20, 16000))
        assertEquals(listOf("Milanko-", "vić’s"), prepared.words.take(2).map { it.text })
        assertEquals(2, prepared.words.take(2).flatMap { it.boxes }.size)
    }

    @Test fun previousNormalizerAudioIsInvalidatedWithoutChangingDocumentIdentity() {
        val original = page("An unchanged sentence.")
        val speech = EnglishNormalizer().normalize(original.words)
        assertNotEquals(cacheKey("doc", speech, "model", "voice", "settings", "align", "english-3"),
            cacheKey("doc", speech, "model", "voice", "settings", "align"))
        assertEquals(original, page("An unchanged sentence."))
    }
}
