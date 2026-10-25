package app.readflow.speech

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class SpeechRuntimeConfigTest {
    @Test fun validatesBoundedThreadCounts() {
        for (threads in listOf(1, 2, 3, 4, 8)) assertEquals(threads, SpeechRuntimeConfig(threads).threads)
        for (threads in listOf(0, -1, 9, Int.MAX_VALUE)) {
            try { SpeechRuntimeConfig(threads); fail("Accepted $threads threads") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun writesRealSherpaSessionKeysAndKeepsSpinModesSeparate() {
        val directory = Files.createTempDirectory("readflow-runtime").toFile()
        try {
            val sleeping = SpeechRuntimeConfig(2, false).sherpaProvider(directory)
            val spinning = SpeechRuntimeConfig(4, true).sherpaProvider(directory)
            assertTrue(sleeping.startsWith("cpu:"))
            assertNotEquals(sleeping, spinning)
            assertEquals("SessionConfig.session.intra_op.allow_spinning=0\nSessionConfig.session.inter_op.allow_spinning=0\n", File(sleeping.removePrefix("cpu:")).readText())
            assertEquals("SessionConfig.session.intra_op.allow_spinning=1\nSessionConfig.session.inter_op.allow_spinning=1\n", File(spinning.removePrefix("cpu:")).readText())
            assertEquals(sleeping, SpeechRuntimeConfig(3, false).sherpaProvider(directory))
        } finally { directory.deleteRecursively() }
    }

    @Test fun concurrentSettingsCreationNeverLeavesPartialFiles() {
        val directory = Files.createTempDirectory("readflow-runtime").toFile()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = (0 until 20).map { index -> pool.submit<String> {
                SpeechRuntimeConfig(2, index % 2 == 0).sherpaProvider(directory)
            } }
            results.forEach { result -> assertEquals(2, File(result.get().removePrefix("cpu:")).readLines().size) }
            assertEquals(2, directory.listFiles()!!.size)
        } finally { pool.shutdownNow(); directory.deleteRecursively() }
    }
}
