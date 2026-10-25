package app.readflow.benchmark

import app.readflow.core.DocumentPipeline
import app.readflow.core.ExtractedPage
import app.readflow.core.SpeechPlanner
import app.readflow.core.TextElement
import org.junit.Assert.*
import org.junit.Test

class BenchmarkMetricsTest {
    @Test fun throughputUsesActualTimeAndUnicodeCharacters() {
        assertEquals(3, BenchmarkMetrics.characters("a😀b"))
        assertEquals(1200.0, BenchmarkMetrics.charactersPerMinute(100, 5000)!!, 0.0001)
        assertEquals(2.5, BenchmarkMetrics.preparationSpeed(10.0, 4000)!!, 0.0001)
    }

    @Test fun unavailableOrInvalidDurationsDoNotBecomeInfiniteScores() {
        assertNull(BenchmarkMetrics.charactersPerMinute(100, 0))
        assertNull(BenchmarkMetrics.charactersPerMinute(-1, 1000))
        assertNull(BenchmarkMetrics.preparationSpeed(Double.NaN, 1000))
        assertNull(BenchmarkMetrics.preparationSpeed(10.0, -1))
        assertNull(BenchmarkMetrics.preparationSpeed(0.0, 1000))
    }

    @Test fun canonicalAccentsAndLineWrapsAreEquivalent() {
        assertEquals(0.0, BenchmarkMetrics.characterErrorRate("Milanković's income", "  Milankovic\u0301's\n income  ")!!, 0.0001)
        assertEquals(0.0, BenchmarkMetrics.characterErrorRate("annual return", "annual\u00a0return")!!, 0.0001)
    }

    @Test fun AccentsCasePunctuationAndNumbersStillCountAsErrors() {
        assertTrue(BenchmarkMetrics.characterErrorRate("Milanković", "Milankovic")!! > 0)
        assertEquals(1.0 / 3, BenchmarkMetrics.characterErrorRate("Abc", "abc")!!, 0.0001)
        assertEquals(1.0 / 4, BenchmarkMetrics.characterErrorRate("9.3%", "9,3%")!!, 0.0001)
        assertEquals(1.0 / 4, BenchmarkMetrics.characterErrorRate("2006", "2008")!!, 0.0001)
    }

    @Test fun cerCountsSubstitutionsDeletionsAndInsertions() {
        assertEquals(1.0 / 3, BenchmarkMetrics.characterErrorRate("cat", "cut")!!, 0.0001)
        assertEquals(1.0 / 3, BenchmarkMetrics.characterErrorRate("cat", "at")!!, 0.0001)
        assertEquals(1.0 / 3, BenchmarkMetrics.characterErrorRate("cat", "cats")!!, 0.0001)
        assertEquals(3.0, BenchmarkMetrics.characterErrorRate("a", "abcd")!!, 0.0001)
    }

    @Test fun cerUsesCodePointsRatherThanUtf16Units() {
        assertEquals(0.5, BenchmarkMetrics.characterErrorRate("a😀", "a😃")!!, 0.0001)
        assertEquals(1.0, BenchmarkMetrics.characterErrorRate("a😀", "")!!, 0.0001)
    }

    @Test fun oversizedOutputsAreNotSilentlyScoredUsingTruncation() {
        assertNull(BenchmarkMetrics.characterErrorRate("", "text"))
        assertNull(BenchmarkMetrics.characterErrorRate("a".repeat(4097), "a"))
        assertNull(BenchmarkMetrics.characterErrorRate("a", "b".repeat(8193)))
    }

    @Test fun tableChecksAllCellsInRowOrderRatherThanPresence() {
        assertTrue(BenchmarkMetrics.tableReadingOrderCorrect(BenchmarkFixtures.OCR_REFERENCE))
        val columnMajor = (0..2).flatMap { column -> BenchmarkFixtures.TABLE_ROWS.map { it[column] } }.joinToString(" ")
        assertFalse(BenchmarkMetrics.tableReadingOrderCorrect(columnMajor))
        assertFalse(BenchmarkMetrics.tableReadingOrderCorrect(BenchmarkFixtures.OCR_REFERENCE.replace("2003", "2008")))
        assertFalse(BenchmarkMetrics.tableReadingOrderCorrect(BenchmarkFixtures.OCR_REFERENCE.replace("\$30,000", "")))
    }

    @Test fun optionsBoundWorkAndDeduplicateModels() {
        assertEquals(listOf("kokoro"), BenchmarkOptions(speechModels = listOf("kokoro", "kokoro")).validated().speechModels)
        assertThrows(IllegalArgumentException::class.java) { BenchmarkOptions(repetitions = 100).validated() }
        assertThrows(IllegalArgumentException::class.java) { BenchmarkOptions(ocrEngines = listOf("cloud")).validated() }
        assertThrows(IllegalArgumentException::class.java) { BenchmarkOptions(speechModels = emptyList(), ocrEngines = emptyList()).validated() }
    }

    @Test fun fixturesPassTheRealSpeechPlannerIncludingCurrencyRangeAndAccent() {
        for (text in BenchmarkFixtures.SPEECH) {
            val page = DocumentPipeline().process("benchmark", 0,
                ExtractedPage(listOf(TextElement(text, emptyList())), 0f, 0f, "Synthetic test"))
            val prepared = SpeechPlanner().prepare(page.words).toList()
            assertTrue(prepared.isNotEmpty())
            assertEquals(page.words, prepared.flatMap { it.words })
            assertTrue(prepared.all { it.speech.text.length <= 220 })
        }
    }
}
