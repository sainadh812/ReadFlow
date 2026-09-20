package app.readflow.playback

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun lastNativeStageSurvivesAppRestart() = runBlocking {
        val directory = temporary.newFolder()
        PlaybackDiagnostics(directory).record("kokoro", PlaybackStage.SYNTHESIZING)
        val restored = PlaybackDiagnostics(directory).checkpoint()!!
        assertEquals("kokoro", restored.model)
        assertEquals(PlaybackStage.SYNTHESIZING, restored.stage)
        assertTrue(restored.timestampMs > 0)
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
}
