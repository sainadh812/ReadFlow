package app.readflow.speech

import app.readflow.core.TtsEngine
import app.readflow.core.Voice
import java.io.File

/** Build-specific engine boundary. The Play variant has no Sherpa dependency. */
internal object SpeechDistribution {
    const val IS_PLAY_BUILD = true
    const val DEFAULT_PHONEMIZER = "misaki"
    val benchmarkModels = listOf("kokoro-misaki")
    fun newLegacyEngine(directory: (String) -> File, voices: (String) -> List<Voice>): TtsEngine =
        error("This build supports Kokoro with Misaki only")
    suspend fun configureLegacy(engine: TtsEngine, config: SpeechRuntimeConfig) {
        error("This build supports Kokoro with Misaki only")
    }
}
