package app.readflow.speech

import app.readflow.core.TtsEngine
import app.readflow.core.Voice
import java.io.File

/** Build-specific engine boundary. The Play variant has no Sherpa dependency. */
internal object SpeechDistribution {
    const val IS_PLAY_BUILD = false
    const val DEFAULT_PHONEMIZER = "espeak"
    val benchmarkModels = listOf("kokoro", "kokoro-misaki", "pocket", "sc-kokoro")
    fun newLegacyEngine(directory: (String) -> File, voices: (String) -> List<Voice>): TtsEngine =
        SherpaEngine(directory, voices)
    suspend fun configureLegacy(engine: TtsEngine, config: SpeechRuntimeConfig) {
        (engine as SherpaEngine).configure(config)
    }
}
