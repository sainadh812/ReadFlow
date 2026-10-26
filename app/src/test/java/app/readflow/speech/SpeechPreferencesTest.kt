package app.readflow.speech

import app.readflow.data.Preferences
import org.junit.Assert.*
import org.junit.Test

class SpeechPreferencesTest {
    @Test fun releaseMigrationKeepsKokoroVoiceAndReaderSettings() {
        val before = Preferences(voice = "2", kokoroPhonemizer = "espeak", dark = true,
            fontSize = 28, speed = 1.4f, cacheMb = 768, ocrEngine = "paddleocr")
        val after = normalizedSpeechPreferences(before, playBuild = true)
        assertEquals(before.copy(kokoroPhonemizer = "misaki"), after)
        assertNotEquals(speechSynthesisRecipe(before), speechSynthesisRecipe(after))
    }

    @Test fun releaseMigrationReplacesPocketVoiceWithoutChangingOtherSettings() {
        val before = Preferences(model = "pocket", voice = "alba", speechThreads = 4)
        val after = normalizedSpeechPreferences(before, playBuild = true)
        assertEquals(before.copy(model = "kokoro", voice = "0", kokoroPhonemizer = "misaki"), after)
        assertEquals(after, normalizedSpeechPreferences(after, playBuild = true))
    }

    @Test fun prototypeKeepsExplicitLegacyChoices() {
        val before = Preferences(model = "pocket", voice = "alba", kokoroPhonemizer = "espeak")
        assertEquals(before, normalizedSpeechPreferences(before, playBuild = false))
    }
}
