package app.readflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readflow.speech.MisakiKokoroEngine
import app.readflow.speech.SpeechRuntimeConfig
import app.readflow.speech.misaki.MisakiFrontend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Loads the packaged Android JNI/Go/ORT chain, not a host-library substitute. */
@RunWith(AndroidJUnit4::class)
class MisakiNativeTest {
    @Test fun packagedFrontendSupportsIndependentOwnersAndReopening() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withContext(Dispatchers.Default) {
            val library = MisakiFrontend.ortLibraryPath(context)
            val first = MisakiFrontend(library)
            try {
                val input = "The wind blew while I wound the clock."
                val expected = first.phonemize(input)
                assertTrue(expected.isNotBlank())
                MisakiFrontend(library).use { second ->
                    assertEquals(expected, second.phonemize(input))
                    first.close()
                    assertEquals(expected, second.phonemize(input))
                }
                try { first.phonemize(input); fail("Closed session was reused") }
                catch (_: IllegalStateException) { }
                MisakiFrontend(library).use { reopened -> assertEquals(expected, reopened.phonemize(input)) }
            } finally { first.close() }
        }
    }

    /** Opt-in downloads the existing Kokoro pack, then runs the actual Android engine. */
    @Test fun verifiedKokoroPackProducesAudioWithMisaki() = runBlocking {
        assumeTrue("Explicit model/device test not enabled", InstrumentationRegistry.getArguments().getString("models") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ReadFlowApp
        app.models.install("kokoro")
        app.models.verifyInstalled("kokoro")
        withContext(Dispatchers.Default) {
            MisakiFrontend(MisakiFrontend.ortLibraryPath(app)).use { frontend ->
                val engine = MisakiKokoroEngine(app.models::directory,
                    { id -> app.models.packs.first { it.id == id }.voices }, { frontend.phonemize(it) })
                try {
                    engine.configure(SpeechRuntimeConfig(2, false))
                    engine.load("kokoro", "0")
                    val audio = engine.synthesize("Milankovitch's example compares income and expenses.") { true }
                    assertEquals(24_000, audio.sampleRate)
                    assertTrue(audio.samples.size in 24_000..960_000)
                    assertTrue(audio.samples.all(Float::isFinite))
                    assertTrue(audio.samples.any { kotlin.math.abs(it) > .01f })
                } finally { engine.unload() }
            }
        }
    }
}
