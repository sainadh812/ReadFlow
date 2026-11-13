package app.readflow.data

import org.junit.Assert.*
import org.junit.Test

class ReaderLightingPreferencesTest {
    @Test fun upgradesFollowPhoneBrightnessAndKeepWarmLightOff() {
        val value = ReaderLightingSettings.from(Preferences())
        assertTrue(value.usePhoneBrightness)
        assertEquals(-1f, value.windowBrightness, 0f)
        assertFalse(value.warmLightEnabled)
        assertEquals(35, value.warmLightStrength)
        assertEquals(0f, value.warmOverlayAlpha, 0f)
    }

    @Test fun invalidSavedLevelsCannotBlackOutOrObscureThePage() {
        val value = ReaderLightingSettings.from(Preferences(usePhoneBrightness = false,
            readerBrightnessPercent = -300, warmLightEnabled = true, warmLightStrength = 300))
        assertEquals(1, value.brightnessPercent)
        assertEquals(.01f, value.windowBrightness, .0001f)
        assertEquals(100, value.warmLightStrength)
        assertEquals(.22f, value.warmOverlayAlpha, .0001f)
        assertEquals(1f, value.copy(brightnessPercent = 600).windowBrightness, 0f)
        assertEquals(0f, value.copy(warmLightStrength = -40).warmOverlayAlpha, 0f)
    }

    @Test fun switchingOffKeepsManualLevelsForNextUse() {
        val saved = ReaderLightingSettings(false, 24, true, 78)
        val off = saved.copy(usePhoneBrightness = true, warmLightEnabled = false).normalized()
        assertEquals(24, off.brightnessPercent)
        assertEquals(78, off.warmLightStrength)
        assertEquals(saved, off.copy(usePhoneBrightness = false, warmLightEnabled = true))
    }
}
