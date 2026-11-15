package app.readflow.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.*
import org.junit.Test

class AppThemeTest {
    @Test fun upgradesAndUnknownChoicesUseWarmWithoutChangingReaderPaper() {
        for (id in listOf(null, "", "removed-theme")) assertEquals(AppTheme.WARM_LIBRARY, AppTheme.fromId(id))
        assertEquals("warm_library", Preferences(dark = true).appTheme)
        assertEquals("evening_paper", Preferences(dark = true).readerTheme)
        AppTheme.entries.forEach { assertEquals(it, AppTheme.fromId(it.id)) }
        assertEquals(listOf("night_reader"), AppTheme.entries.filter { it.dark }.map { it.id })
    }

    @Test fun themeWritesChangeOnlyTheirOwnKeyAndPreserveIndependentReaderSettings() {
        val voice = stringPreferencesKey("voice")
        val readerTheme = stringPreferencesKey("reader_theme")
        val warmth = booleanPreferencesKey("reader_warm_light_enabled")
        val stored = mutablePreferencesOf(voice to "7", readerTheme to "crisp_paper", warmth to true)
        stored.writeAppTheme("night_reader")
        assertEquals("night_reader", stored[appThemePreferenceKey])
        assertEquals("7", stored[voice])
        assertEquals("crisp_paper", stored[readerTheme])
        assertEquals(true, stored[warmth])
        stored.writeAppTheme("unknown")
        assertEquals("warm_library", stored[appThemePreferenceKey])
        assertEquals(4, stored.asMap().size)
    }
}
