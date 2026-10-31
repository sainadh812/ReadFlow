package app.readflow.data

import org.junit.Assert.*
import org.junit.Test

class ReaderAppearancePreferencesTest {
    @Test fun existingDarkReaderMigratesOnceAndExplicitPaperWins() {
        assertEquals("warm_paperback", ReaderAppearancePreferences.theme(null, false))
        assertEquals("evening_paper", ReaderAppearancePreferences.theme(null, true))
        assertEquals("crisp_paper", ReaderAppearancePreferences.theme("crisp_paper", true))
        assertEquals("evening_paper", Preferences(dark = true).readerTheme)
    }

    @Test fun choosingPaperOrAppDarkModePreservesFontAndSavedSize() {
        val saved = Preferences(readerTheme = "crisp_paper", readerFont = "opendyslexic", fontSize = 5,
            voice = "3", speed = 1.5f, swipeRightAdvances = false)
        val withPaper = ReaderAppearancePreferences.normalize(saved.copy(readerTheme = "evening_paper"))
        assertEquals(saved.copy(readerTheme = "evening_paper"), withPaper)
        assertEquals(saved.copy(dark = true), ReaderAppearancePreferences.normalize(saved.copy(dark = true)))
    }

    @Test fun invalidSavedChoicesRecoverWithoutChangingOtherPreferences() {
        val saved = Preferences(readerTheme = "removed", readerFont = "missing", fontSize = 2,
            dark = true, ocrEngine = "paddleocr", showPerformance = false)
        assertEquals(saved.copy(readerTheme = "evening_paper", readerFont = "system_serif", fontSize = 5),
            ReaderAppearancePreferences.normalize(saved))
        assertEquals(34, ReaderAppearancePreferences.normalize(saved.copy(fontSize = 99)).fontSize)
    }

    @Test fun everyFontAndBothSizeEndpointsRemainValid() {
        ReaderAppearancePreferences.fonts.forEach { font ->
            listOf(5, 21, 34).forEach { size ->
                val value = Preferences(readerFont = font, fontSize = size)
                assertEquals(value, ReaderAppearancePreferences.normalize(value))
            }
        }
    }
}
