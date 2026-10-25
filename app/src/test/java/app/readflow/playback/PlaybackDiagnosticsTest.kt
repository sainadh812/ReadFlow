package app.readflow.playback

import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
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
}
