package app.readflow.speech

import app.readflow.benchmark.BenchmarkOptions
import app.readflow.data.Preferences
import org.junit.Assert.*
import org.junit.Test

class PlaySpeechDistributionTest {
    @Test fun playDefaultsAndExecutionOnlyUseMisaki() {
        assertTrue(SpeechDistribution.IS_PLAY_BUILD)
        assertEquals("misaki", Preferences().kokoroPhonemizer)
        assertEquals(listOf("kokoro-misaki"), BenchmarkOptions().validatedForExecution().speechModels)
        for (legacy in listOf("kokoro", "pocket", "sc-kokoro")) {
            assertThrows(IllegalArgumentException::class.java) {
                BenchmarkOptions(speechModels = listOf(legacy)).validatedForExecution()
            }
        }
    }

    @Test fun legacyNativeClassesAreAbsentFromReleaseRuntime() {
        for (type in listOf("app.readflow.speech.SherpaEngine", "app.readflow.speech.SherpaAudioCallback", "com.k2fsa.sherpa.onnx.OfflineTts")) {
            assertThrows(ClassNotFoundException::class.java) { Class.forName(type, false, javaClass.classLoader) }
        }
    }
}
