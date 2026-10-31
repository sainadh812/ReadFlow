package app.readflow.speech

import app.readflow.data.Preferences
import app.readflow.speech.misaki.MisakiFrontend

// Both the pinned frontend and its v0.19 phone adapter determine pronunciation.
// Bump this identity whenever either changes, so old PCM cannot mask a trial.
internal const val MISAKI_RECIPE_VERSION = MisakiFrontend.VERSION + "-" + MisakiKokoroEngine.ADAPTER_VERSION
private const val LEGACY_RECIPE = "steps=5;temperature=.7;speed=1;threads=2"

internal fun usesMisaki(preferences: Preferences) =
    preferences.model == "kokoro" && preferences.kokoroPhonemizer == "misaki"

internal fun speechSynthesisRecipe(preferences: Preferences): String =
    if (usesMisaki(preferences)) "$LEGACY_RECIPE;phonemizer=$MISAKI_RECIPE_VERSION" else LEGACY_RECIPE
