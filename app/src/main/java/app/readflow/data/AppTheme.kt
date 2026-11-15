package app.readflow.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey

enum class AppTheme(val id: String, val label: String, val dark: Boolean) {
    WARM_LIBRARY("warm_library", "Warm Library", false),
    MODERN_FOCUS("modern_focus", "Modern Focus", false),
    NIGHT_READER("night_reader", "Night Reader", true);

    companion object {
        fun fromId(value: String?): AppTheme = entries.firstOrNull { it.id == value } ?: WARM_LIBRARY
    }
}

internal val appThemePreferenceKey = stringPreferencesKey("app_theme")

/** Only this key changes; an old copy of speech/reader settings cannot undo a theme choice. */
internal fun MutablePreferences.writeAppTheme(id: String) {
    this[appThemePreferenceKey] = AppTheme.fromId(id).id
}
