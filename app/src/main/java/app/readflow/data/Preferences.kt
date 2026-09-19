package app.readflow.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("reader")
data class Preferences(val model: String = "kokoro", val voice: String = "0", val fontSize: Int = 21, val dark: Boolean = false, val speed: Float = 1f, val cacheMb: Int = 512, val skipMargins: Boolean = false, val latencyMs: Int = 0)
class PreferenceStore(private val context: Context) {
    private val model = stringPreferencesKey("model"); private val voice = stringPreferencesKey("voice")
    private val font = intPreferencesKey("font"); private val dark = booleanPreferencesKey("dark")
    private val speed = floatPreferencesKey("speed"); private val cache = intPreferencesKey("cache_mb")
    private val margins = booleanPreferencesKey("skip_margins"); private val latency = intPreferencesKey("latency_ms")
    val values = context.store.data.map { Preferences(it[model] ?: "kokoro", it[voice] ?: "0", it[font] ?: 21, it[dark] ?: false, it[speed] ?: 1f, it[cache] ?: 512, it[margins] ?: false, it[latency] ?: 0) }
    suspend fun set(value: Preferences) { context.store.edit {
        it[model] = value.model; it[voice] = value.voice; it[font] = value.fontSize.coerceIn(16, 34); it[dark] = value.dark
        it[speed] = value.speed.coerceIn(.75f, 2.5f); it[cache] = value.cacheMb.coerceIn(128, 2048)
        it[margins] = value.skipMargins; it[latency] = value.latencyMs.coerceIn(0, 500)
    } }
}
