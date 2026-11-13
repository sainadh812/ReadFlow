package app.readflow.data

/** Reader-only light settings. The document and the phone's global settings stay untouched. */
data class ReaderLightingSettings(
    val usePhoneBrightness: Boolean = true,
    val brightnessPercent: Int = 60,
    val warmLightEnabled: Boolean = false,
    val warmLightStrength: Int = 35,
) {
    fun normalized(): ReaderLightingSettings = copy(
        brightnessPercent = brightnessPercent.coerceIn(1, 100),
        warmLightStrength = warmLightStrength.coerceIn(0, 100),
    )

    /** -1 tells this window to use the phone setting. 1% avoids a black reader. */
    val windowBrightness: Float
        get() = if (usePhoneBrightness) -1f else brightnessPercent.coerceIn(1, 100) / 100f

    /** Even at full strength the wash stays translucent enough to retain text and red marks. */
    val warmOverlayAlpha: Float
        get() = if (warmLightEnabled) warmLightStrength.coerceIn(0, 100) / 100f * .22f else 0f

    companion object {
        fun from(preferences: Preferences): ReaderLightingSettings = ReaderLightingSettings(
            preferences.usePhoneBrightness, preferences.readerBrightnessPercent,
            preferences.warmLightEnabled, preferences.warmLightStrength,
        ).normalized()
    }
}
