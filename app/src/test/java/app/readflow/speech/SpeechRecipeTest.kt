package app.readflow.speech

import app.readflow.data.Preferences
import org.junit.Assert.*
import org.junit.Test

class SpeechRecipeTest {
    @Test fun changingPronunciationCannotReuseLegacyAudio() {
        val original = Preferences()
        assertNotEquals(speechSynthesisRecipe(original), speechSynthesisRecipe(original.copy(kokoroPhonemizer = "misaki")))
        assertEquals("steps=5;temperature=.7;speed=1;threads=2", speechSynthesisRecipe(original))
    }

    @Test fun cpuChangesAndPocketSelectionDoNotInvalidateUnrelatedAudio() {
        val misaki = Preferences(kokoroPhonemizer = "misaki")
        assertEquals(speechSynthesisRecipe(misaki), speechSynthesisRecipe(misaki.copy(speechThreads = 4, allowSpinning = true)))
        assertEquals(speechSynthesisRecipe(Preferences(model = "pocket")), speechSynthesisRecipe(misaki.copy(model = "pocket")))
        assertFalse(usesMisaki(misaki.copy(model = "pocket")))
    }
}
