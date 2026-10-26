package app.readflow.speech

import app.readflow.data.Preferences

/** Upgrade old speech choices without changing documents or other reader preferences. */
internal fun normalizedSpeechPreferences(value: Preferences, playBuild: Boolean = SpeechDistribution.IS_PLAY_BUILD): Preferences =
    if (!playBuild) value else value.copy(
        model = "kokoro",
        voice = if (value.model == "kokoro") value.voice else "0",
        kokoroPhonemizer = "misaki",
    )
