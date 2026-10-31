package app.readflow.playback

import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun successfulStagesStayInMemoryAndDoNotCreateLogs() = runBlocking {
        val directory = temporary.newFolder()
        val diagnostics = PlaybackDiagnostics(directory)
        diagnostics.record("kokoro", PlaybackStage.SYNTHESIZING)
        assertEquals(PlaybackStage.SYNTHESIZING, diagnostics.checkpoint()!!.stage)
        assertNull(PlaybackDiagnostics(directory).checkpoint())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun failureReportNeverPersistsExceptionMessagesOrUnknownModelText() = runBlocking {
        val directory = temporary.newFolder()
        val privateText = "Private document sentence https://private.example/report"
        val error = IllegalStateException(privateText, UnsatisfiedLinkError(privateText))
        val diagnostics = PlaybackDiagnostics(directory)
        diagnostics.record(privateText, PlaybackStage.FAILED, error)
        val restored = diagnostics.checkpoint()!!
        assertEquals("unknown", restored.model)
        assertEquals(listOf("java.lang.IllegalStateException", "java.lang.UnsatisfiedLinkError"), restored.failureTypes)
        assertFalse(File(directory, "playback.json").readText().contains("private", ignoreCase = true))
    }

    @Test fun misakiSessionAndFailureRemainDistinctFromLegacyKokoro() = runBlocking {
        val directory = temporary.newFolder()
        val diagnostics = PlaybackDiagnostics(directory)
        diagnostics.beginSession("kokoro", "0")
        diagnostics.recordPreparation(false, 1000, 200, 1200, 5000, 90)
        diagnostics.beginSession("kokoro-misaki", "0")
        assertEquals("kokoro-misaki", diagnostics.performance.value.model)
        assertEquals(0L, diagnostics.performance.value.totalPreparations)
        diagnostics.record("kokoro-misaki", PlaybackStage.FAILED, IllegalStateException("Synthetic failure"))
        assertEquals("kokoro-misaki", PlaybackDiagnostics(directory).checkpoint()!!.model)
    }

    @Test fun corruptOrMissingCheckpointDoesNotPreventOpeningDiagnostics() = runBlocking {
        val directory = temporary.newFolder()
        val diagnostics = PlaybackDiagnostics(directory)
        assertNull(diagnostics.checkpoint())
        File(directory, "playback.json").writeText("interrupted write")
        assertNull(diagnostics.checkpoint())
        diagnostics.record("pocket", PlaybackStage.AUDIO_READY)
        assertEquals(PlaybackStage.AUDIO_READY, diagnostics.checkpoint()!!.stage)
        assertFalse(File(directory, "playback.part").exists())
    }

    @Test fun freshDiagnosticsHaveNoInventedPerformanceMeasurements() {
        val diagnostics = PlaybackDiagnostics(temporary.newFolder())
        val summary = diagnostics.performanceSummary()
        assertEquals(0L, summary.totalPreparations)
        assertEquals(0L, summary.totalUnderruns)
        assertNull(summary.meanSynthesisMs)
        assertNull(summary.meanAlignmentMs)
        assertNull(summary.preparationP95Ms)
        assertNull(summary.generatedPreparationToAudioRatio)
        assertNull(summary.synthesisCharactersPerMinute)
        assertNull(summary.generatedAudioSecondsPerPreparationSecond)
        assertNull(summary.canKeepUp(1f))
        assertNull(diagnostics.extractionPerformance.value)
        assertNull(summary.readyChunks)
        assertTrue(diagnostics.performanceReport().contains("No completed preparation measurements"))
    }

    @Test fun cacheHitsDoNotInflateMeasuredGenerationThroughput() {
        val diagnostics = PlaybackDiagnostics(temporary.newFolder())
        diagnostics.recordPreparation(false, 200, 100, 400, 1_000)
        diagnostics.recordPreparation(false, 400, 200, 800, 2_000)
        diagnostics.recordPreparation(true, 0, 0, 10, 20_000)
        diagnostics.recordUnderrun()
        diagnostics.recordBuffer(5, 18_000)

        val summary = diagnostics.performanceSummary()
        assertEquals(3L, summary.totalPreparations)
        assertEquals(1L, summary.totalUnderruns)
        assertEquals(1, summary.recentCacheHits)
        assertEquals(23_000L, summary.recentAudioDurationMs)
        assertEquals(1_210L, summary.recentPreparationDurationMs)
        assertEquals(300.0, summary.meanSynthesisMs!!, 0.001)
        assertEquals(150.0, summary.meanAlignmentMs!!, 0.001)
        assertEquals(0.4, summary.generatedPreparationToAudioRatio!!, 0.001)
        assertEquals(800L, summary.preparationP95Ms)
        assertEquals(5, summary.readyChunks)
        assertEquals(18_000L, summary.bufferedListeningMs)
        assertTrue(diagnostics.performanceReport().contains("Generated preparation/audio ratio: 0.40"))
    }

    @Test fun recentWindowDropsOldMeasurementsButPreservesLifetimeCounts() {
        val diagnostics = PlaybackDiagnostics(temporary.newFolder())
        diagnostics.recordPreparation(true, 0, 0, 100_000, 500_000)
        for (duration in 1L..64L) {
            diagnostics.recordPreparation(false, duration, 0, duration, duration * 10)
        }
        val summary = diagnostics.performanceSummary()
        assertEquals(65L, summary.totalPreparations)
        assertEquals(64, summary.recentPreparations)
        assertEquals(0, summary.recentCacheHits)
        assertEquals(2_080L, summary.recentPreparationDurationMs)
        assertEquals(20_800L, summary.recentAudioDurationMs)
        assertEquals(61L, summary.preparationP95Ms)
        assertEquals(0.1, summary.generatedPreparationToAudioRatio!!, 0.001)
    }

    @Test fun successfulMeasurementsStayInMemoryAndHaveNoInputIdentifiers() = runBlocking {
        val directory = temporary.newFolder()
        val diagnostics = PlaybackDiagnostics(directory)
        val privateText = "secret-book https://private.example/audio.wav"
        diagnostics.record(privateText, PlaybackStage.AUDIO_READY)
        diagnostics.recordPreparation(false, 5, 10, 20, 2_000)
        diagnostics.recordUnderrun()
        diagnostics.recordBuffer(5, 10_000)
        assertFalse(diagnostics.performanceReport().contains(privateText))
        assertFalse(diagnostics.performanceReport().contains("private.example"))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        val fresh = PlaybackDiagnostics(directory).performanceSummary()
        assertEquals(0L, fresh.totalPreparations)
        assertEquals(0L, fresh.totalUnderruns)
        assertNull(fresh.bufferedListeningMs)
    }

    @Test fun concurrentPlaybackAndPreparationUpdatesDoNotLoseCounts() {
        val diagnostics = PlaybackDiagnostics(temporary.newFolder())
        val start = CountDownLatch(1)
        val workers = List(4) {
            thread {
                start.await()
                repeat(250) {
                    diagnostics.recordPreparation(false, 1, 1, 2, 10)
                    diagnostics.recordUnderrun()
                    diagnostics.recordBuffer(5, 50)
                    diagnostics.performanceSummary()
                }
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        val summary = diagnostics.performanceSummary()
        assertEquals(1_000L, summary.totalPreparations)
        assertEquals(1_000L, summary.totalUnderruns)
        assertEquals(64, summary.recentPreparations)
        assertEquals(128L, summary.recentPreparationDurationMs)
    }

    @Test fun invalidDurationsCannotProduceNegativeMetricsOrInfiniteRatios() {
        val diagnostics = PlaybackDiagnostics(temporary.newFolder())
        diagnostics.recordPreparation(false, -1, -2, -3, 0)
        diagnostics.recordBuffer(-1, -1)
        val summary = diagnostics.performanceSummary()
        assertEquals(0L, summary.recentPreparationDurationMs)
        assertEquals(0.0, summary.meanSynthesisMs!!, 0.001)
        assertEquals(0.0, summary.meanAlignmentMs!!, 0.001)
        assertNull(summary.generatedPreparationToAudioRatio)
        assertEquals(0, summary.readyChunks)
        assertEquals(0L, summary.bufferedListeningMs)
    }

    @Test fun characterThroughputUsesTotalCharactersAndSynthesisTimeAndExcludesCache() {
        val diagnostics = PlaybackDiagnostics(temporary.newFolder())
        diagnostics.recordPreparation(false, 1_000, 200, 2_000, 4_000, characters = 100)
        diagnostics.recordPreparation(false, 3_000, 400, 3_500, 7_000, characters = 200)
        diagnostics.recordPreparation(true, 1, 0, 1, 90_000, characters = 50_000)

        val summary = diagnostics.performance.value
        assertEquals(4_500.0, summary.synthesisCharactersPerMinute!!, 0.001)
        assertEquals(2, summary.synthesisCharacterSamples)
        assertEquals(2, summary.generatedPreparationSamples)
        assertEquals(2, summary.generatedPreparations)
        // Preparation includes model loading and alignment; synthesis throughput does not.
        assertEquals(2.0, summary.generatedAudioSecondsPerPreparationSecond!!, 0.001)
        assertEquals(1.0, summary.throughputHeadroom(2f)!!, 0.001)
        assertEquals(true, summary.canKeepUp(2f))
        assertEquals(false, summary.canKeepUp(3f))
        assertNull(summary.throughputHeadroom(0f))
        assertNull(summary.throughputHeadroom(Float.NaN))
        assertNull(summary.throughputHeadroom(Float.POSITIVE_INFINITY))
    }

    @Test fun cacheOnlyAndUnmeasurableSamplesDoNotClaimFastModelPerformance() {
        val diagnostics = PlaybackDiagnostics(temporary.newFolder())
        diagnostics.recordPreparation(true, 1, 0, 1, 60_000, characters = 5_000)
        assertNull(diagnostics.performance.value.synthesisCharactersPerMinute)
        assertNull(diagnostics.performance.value.generatedAudioSecondsPerPreparationSecond)
        diagnostics.recordPreparation(false, 0, -1, 0, 60_000, characters = 5_000)
        diagnostics.recordPreparation(false, -1, 1, -1, 60_000, characters = 5_000)
        assertNull(diagnostics.performance.value.synthesisCharactersPerMinute)
        assertNull(diagnostics.performance.value.generatedAudioSecondsPerPreparationSecond)

        diagnostics.recordPreparation(false, 100, 100, 300, 0, characters = 0)
        assertNull(diagnostics.performance.value.synthesisCharactersPerMinute)
        assertNull(diagnostics.performance.value.generatedPreparationToAudioRatio)
        assertEquals(0, diagnostics.performance.value.generatedPreparationSamples)
    }

    @Test fun eachReadingSessionResetsWindowIncludingSameModelAndDifferentVoices() {
        val diagnostics = PlaybackDiagnostics(temporary.newFolder())
        diagnostics.beginSession("kokoro", "0")
        diagnostics.recordPreparation(false, 1_000, 200, 1_500, 10_000, 100)
        diagnostics.recordBuffer(4, 15_000)
        diagnostics.recordUnderrun()
        diagnostics.beginSession("kokoro", "0")
        assertEquals(0L, diagnostics.performance.value.totalPreparations)
        assertEquals(0L, diagnostics.performance.value.totalUnderruns)
        assertNull(diagnostics.performance.value.bufferedListeningMs)
        assertNull(diagnostics.performance.value.synthesisCharactersPerMinute)

        diagnostics.recordPreparation(false, 1_000, 200, 1_500, 10_000, 100)
        diagnostics.beginSession("pocket", "alba")
        val summary = diagnostics.performance.value
        assertEquals("pocket", summary.model)
        assertEquals("alba", summary.voice)
        assertEquals(0, summary.recentPreparations)
        assertNull(summary.meanSynthesisMs)
        diagnostics.recordPreparation(false, 4_000, 100, 5_000, 5_000, 40)
        assertEquals(600.0, diagnostics.performance.value.synthesisCharactersPerMinute!!, 0.001)
    }

    @Test fun preparationBufferAndUnderrunChangesAreObservable() = runBlocking {
        val diagnostics = PlaybackDiagnostics(temporary.newFolder())
        val observed = mutableListOf<PlaybackPerformanceSummary>()
        val collection = launch(Dispatchers.Unconfined) { diagnostics.performance.collect { observed += it } }
        try {
            diagnostics.beginSession("kokoro", "0")
            diagnostics.recordPreparation(false, 100, 20, 200, 1_000, 10)
            diagnostics.recordBuffer(5, 15_000)
            diagnostics.recordUnderrun()
            assertTrue(observed.any { it.model == "kokoro" && it.recentPreparations == 0 })
            assertTrue(observed.any { it.recentPreparations == 1 && it.readyChunks == null })
            assertTrue(observed.any { it.readyChunks == 5 && it.totalUnderruns == 0L })
            assertEquals(1L, observed.last().totalUnderruns)
            assertSame(diagnostics.performance.value, diagnostics.performanceSummary())
        } finally {
            collection.cancel()
        }
    }

    @Test fun frequentBufferUpdatesPreservePreparationWindowAndRates() {
        val diagnostics = PlaybackDiagnostics(temporary.newFolder())
        for (duration in 1L..64L) {
            diagnostics.recordPreparation(false, duration, 1, duration + 1, duration * 10, duration.toInt())
        }
        val prepared = diagnostics.performance.value
        repeat(1_000) { diagnostics.recordBuffer(5, it.toLong()) }
        assertEquals(prepared.copy(readyChunks = 5, bufferedListeningMs = 999), diagnostics.performance.value)
    }

    @Test fun extractionMeasurementLabelsActualEngineAndExcludesCachedRates() {
        val diagnostics = PlaybackDiagnostics(temporary.newFolder())
        diagnostics.recordExtraction("ML Kit", 1_200, 1, cached = true)
        assertEquals("ML Kit", diagnostics.extractionPerformance.value!!.engine)
        assertNull(diagnostics.extractionPerformance.value!!.charactersPerMinute)
        diagnostics.recordExtraction("TeleOCR", 1_200, 2_000)
        val measured = diagnostics.extractionPerformance.value!!
        assertEquals("TeleOCR", measured.engine)
        assertEquals(36_000.0, measured.charactersPerMinute!!, 0.001)
        diagnostics.recordExtraction("PaddleOCR", 1_200, 1_000)
        assertEquals("PaddleOCR", diagnostics.extractionPerformance.value!!.engine)
        assertEquals(72_000.0, diagnostics.extractionPerformance.value!!.charactersPerMinute!!, 0.001)
        diagnostics.recordExtraction("Embedded PDF", 1_200, 0)
        assertNull(diagnostics.extractionPerformance.value!!.charactersPerMinute)
        diagnostics.recordExtraction("ML Kit", -1, -1)
        assertEquals(0, diagnostics.extractionPerformance.value!!.characters)
        assertEquals(0L, diagnostics.extractionPerformance.value!!.elapsedMs)
        assertNull(diagnostics.extractionPerformance.value!!.charactersPerMinute)
    }

    @Test fun cachedPageOpeningAndPlaybackDoNotEraseTheLatestMeasuredOcrRun() {
        val diagnostics = PlaybackDiagnostics(temporary.newFolder())
        diagnostics.recordExtraction("TeleOCR", 1_200, 2_000)
        val measured = diagnostics.extractionPerformance.value
        // Retry OCR reopens the saved page; pressing Play reads that cached page again.
        diagnostics.recordExtraction("TeleOCR", 1_200, 0, cached = true)
        diagnostics.recordExtraction("TeleOCR", 1_200, 0, cached = true)
        assertSame(measured, diagnostics.extractionPerformance.value)
        assertEquals(36_000.0, diagnostics.extractionPerformance.value!!.charactersPerMinute!!, 0.001)

        // Another cache hit must not claim a fresh measurement for that page's engine.
        diagnostics.recordExtraction("ML Kit", 500, 0, cached = true)
        assertSame(measured, diagnostics.extractionPerformance.value)
        diagnostics.recordExtraction("ML Kit", 600, 1_000)
        assertEquals("ML Kit", diagnostics.extractionPerformance.value!!.engine)
        assertEquals(600, diagnostics.extractionPerformance.value!!.characters)
    }

    @Test fun sessionAndExtractionReportsCannotContainUnrecognizedInputText() {
        val directory = temporary.newFolder()
        val diagnostics = PlaybackDiagnostics(directory)
        val privateText = "Private document sentence https://private.example/report"
        diagnostics.beginSession(privateText, privateText)
        diagnostics.recordExtraction(privateText, 10, 1_000)
        val report = diagnostics.performanceReport()
        assertTrue(report.contains("Speech model: unknown; voice: unknown"))
        assertTrue(report.contains("Other extraction"))
        assertFalse(report.contains(privateText))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun fallbackCannotAttributeFailedTeleOcrTimeToMlKitThroughput() {
        val diagnostics = PlaybackDiagnostics(temporary.newFolder())
        diagnostics.recordExtraction("ML Kit fallback", 1_200, 60_000)
        val measured = diagnostics.extractionPerformance.value!!
        assertEquals("ML Kit fallback", measured.engine)
        assertEquals(1_200, measured.characters)
        assertEquals(60_000L, measured.elapsedMs)
        assertFalse(measured.cached)
        assertNull(measured.charactersPerMinute)
    }
}
