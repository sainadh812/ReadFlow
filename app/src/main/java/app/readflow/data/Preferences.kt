package app.readflow.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import app.readflow.speech.SpeechDistribution
import app.readflow.speech.normalizedSpeechPreferences

private val Context.store by preferencesDataStore("reader")
data class Preferences(val model: String = "kokoro", val voice: String = "0", val fontSize: Int = 21, val dark: Boolean = false, val speed: Float = 1f, val cacheMb: Int = 512, val skipMargins: Boolean = false, val latencyMs: Int = 0, val swipePages: Boolean = true, val swipeRightAdvances: Boolean = false,
    val ocrQuality: String = "balanced", val ocrEngine: String = "mlkit", val showPerformance: Boolean = true,
    val speechThreads: Int = 2, val alignmentThreads: Int = 2, val allowSpinning: Boolean = false,
    val kokoroPhonemizer: String = SpeechDistribution.DEFAULT_PHONEMIZER,
    val readerTheme: String = if (dark) "evening_paper" else "warm_paperback", val readerFont: String = "system_serif",
    val pageTurnStyle: String = "curl",
    val usePhoneBrightness: Boolean = true, val readerBrightnessPercent: Int = 60,
    val warmLightEnabled: Boolean = false, val warmLightStrength: Int = 35)
class PreferenceStore(private val context: Context) {
    private val model = stringPreferencesKey("model"); private val voice = stringPreferencesKey("voice")
    private val font = intPreferencesKey("font"); private val dark = booleanPreferencesKey("dark")
    private val speed = floatPreferencesKey("speed"); private val cache = intPreferencesKey("cache_mb")
    private val margins = booleanPreferencesKey("skip_margins"); private val latency = intPreferencesKey("latency_ms")
    private val swipePages = booleanPreferencesKey("swipe_pages")
    // Adopt the approved book direction once on upgrade, then retain an explicit new override.
    private val swipeRight = booleanPreferencesKey("book_swipe_right_advances")
    private val pageTurnStyle = stringPreferencesKey("page_turn_style")
    private val ocrQuality = stringPreferencesKey("ocr_quality"); private val ocrEngine = stringPreferencesKey("ocr_engine")
    private val showPerformance = booleanPreferencesKey("show_performance")
    private val speechThreads = intPreferencesKey("speech_threads")
    private val alignmentThreads = intPreferencesKey("alignment_threads")
    private val allowSpinning = booleanPreferencesKey("allow_spinning")
    private val kokoroPhonemizer = stringPreferencesKey("kokoro_phonemizer")
    private val readerTheme = stringPreferencesKey("reader_theme")
    private val readerFont = stringPreferencesKey("reader_font")
    private val usePhoneBrightness = booleanPreferencesKey("reader_use_phone_brightness")
    private val readerBrightness = intPreferencesKey("reader_brightness_percent")
    private val warmLightEnabled = booleanPreferencesKey("reader_warm_light_enabled")
    private val warmLightStrength = intPreferencesKey("reader_warm_light_strength")
    val values = context.store.data.map { normalizedSpeechPreferences(Preferences(it[model] ?: "kokoro", it[voice] ?: "0", (it[font] ?: 21).coerceIn(5, 34), it[dark] ?: false, it[speed] ?: 1f, it[cache] ?: 512, it[margins] ?: false, it[latency] ?: 0, it[swipePages] ?: true, it[swipeRight] ?: false,
        it[ocrQuality]?.takeIf { quality -> quality in setOf("fast", "balanced", "high") } ?: "balanced",
        it[ocrEngine]?.takeIf { engine -> engine in setOf("mlkit", "teleocr", "paddleocr") } ?: "mlkit", it[showPerformance] ?: true,
        (it[speechThreads] ?: 2).coerceIn(1, 4), (it[alignmentThreads] ?: 2).coerceIn(1, 4), it[allowSpinning] ?: false,
        it[kokoroPhonemizer]?.takeIf { phonemizer -> phonemizer in setOf("espeak", "misaki") } ?: SpeechDistribution.DEFAULT_PHONEMIZER,
        ReaderAppearancePreferences.theme(it[readerTheme], it[dark] ?: false), ReaderAppearancePreferences.font(it[readerFont]),
        ReaderAppearancePreferences.pageTurn(it[pageTurnStyle]),
        it[usePhoneBrightness] ?: true, (it[readerBrightness] ?: 60).coerceIn(1, 100),
        it[warmLightEnabled] ?: false, (it[warmLightStrength] ?: 35).coerceIn(0, 100))) }

    // Lighting is written independently of set(): a stale speech/appearance copy must never
    // revert a newer slider position. DataStore serializes this edit with all other updates.
    suspend fun setReaderLighting(requested: ReaderLightingSettings) {
        val value = requested.normalized()
        context.store.edit {
            it[usePhoneBrightness] = value.usePhoneBrightness
            it[readerBrightness] = value.brightnessPercent
            it[warmLightEnabled] = value.warmLightEnabled
            it[warmLightStrength] = value.warmLightStrength
        }
    }
    suspend fun set(requested: Preferences) { val value = ReaderAppearancePreferences.normalize(normalizedSpeechPreferences(requested)); context.store.edit {
        it[model] = value.model; it[voice] = value.voice; it[font] = value.fontSize.coerceIn(5, 34); it[dark] = value.dark
        it[speed] = value.speed.coerceIn(.75f, 2.5f); it[cache] = value.cacheMb.coerceIn(128, 2048)
        it[swipePages] = value.swipePages; it[swipeRight] = value.swipeRightAdvances
        it[pageTurnStyle] = value.pageTurnStyle
        it[margins] = value.skipMargins; it[latency] = value.latencyMs.coerceIn(0, 500)
        it[ocrQuality] = value.ocrQuality.takeIf { quality -> quality in setOf("fast", "balanced", "high") } ?: "balanced"
        it[ocrEngine] = value.ocrEngine.takeIf { engine -> engine in setOf("mlkit", "teleocr", "paddleocr") } ?: "mlkit"
        it[showPerformance] = value.showPerformance
        it[speechThreads] = value.speechThreads.coerceIn(1, 4)
        it[alignmentThreads] = value.alignmentThreads.coerceIn(1, 4)
        it[allowSpinning] = value.allowSpinning
        it[kokoroPhonemizer] = value.kokoroPhonemizer.takeIf { phonemizer -> phonemizer in setOf("espeak", "misaki") } ?: "espeak"
        it[readerTheme] = value.readerTheme
        it[readerFont] = value.readerFont
    } }
}
