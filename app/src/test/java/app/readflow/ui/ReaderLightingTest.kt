package app.readflow.ui

import app.readflow.data.ReaderLightingSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderLightingTest {
    @Test fun liveDragIgnoresOldStoredValuesAndSavesOnlyOnRelease() = runTest {
        val saved = mutableListOf<ReaderLightingSettings>()
        val initial = ReaderLightingSettings(usePhoneBrightness = false)
        val state = ReaderLightingState(initial, this, { saved += it }) { throw it }
        state.previewBrightness(25)
        state.previewBrightness(26)
        state.acceptStored(initial)
        assertEquals(26, state.settings.brightnessPercent)
        runCurrent()
        assertTrue(saved.isEmpty())
        state.commit()
        state.acceptStored(initial)
        assertEquals(26, state.settings.brightnessPercent)
        runCurrent()
        assertEquals(listOf(initial.copy(brightnessPercent = 26)), saved)
        state.acceptStored(saved.single())
        assertEquals(saved.single(), state.settings)
    }

    @Test fun togglingWarmthKeepsTheDraftAndLateAcknowledgementsCannotUndoANewerChoice() = runTest {
        val saved = mutableListOf<ReaderLightingSettings>()
        val initial = ReaderLightingSettings()
        val state = ReaderLightingState(initial, this, { saved += it }) { throw it }
        state.enableWarmLight(true)
        val first = state.settings
        state.previewWarmth(72)
        state.enableWarmLight(false)
        state.acceptStored(first)
        assertFalse(state.settings.warmLightEnabled)
        assertEquals(72, state.settings.warmLightStrength)
        runCurrent()
        assertEquals(2, saved.size)
        assertEquals(state.settings, saved.last())
        state.acceptStored(saved.last())
        state.enableWarmLight(true)
        assertEquals(72, state.settings.warmLightStrength)
    }

    @Test fun interruptedGestureSavesItsLastVisibleValueExactlyOnce() = runTest {
        val saved = mutableListOf<ReaderLightingSettings>()
        val state = ReaderLightingState(ReaderLightingSettings(), this, { saved += it }) { throw it }
        state.previewWarmth(91)
        state.saveDraft()
        state.saveDraft()
        runCurrent()
        assertEquals(1, saved.size)
        assertEquals(91, saved.single().warmLightStrength)
    }

    @Test fun readerAndDialogRestoreTheirOwnWindowValuesIndependently() {
        var activityValue = .42f
        var dialogValue = -1f
        val reader = ReaderWindowBrightness({ activityValue }, { activityValue = it })
        val dialog = ReaderWindowBrightness({ dialogValue }, { dialogValue = it })
        val manual = ReaderLightingSettings(usePhoneBrightness = false, brightnessPercent = 18)
        reader.update(manual)
        reader.setForeground(true)
        dialog.update(manual)
        dialog.setForeground(true)
        assertEquals(.18f, activityValue, .0001f)
        assertEquals(.18f, dialogValue, .0001f)
        dialog.close()
        assertEquals(-1f, dialogValue, 0f)
        assertEquals(.18f, activityValue, .0001f)
        reader.close()
        assertEquals(.42f, activityValue, 0f)
        reader.close()
        assertEquals(.42f, activityValue, 0f)
    }

    @Test fun backgroundRestoresWindowAndResumeHonorsUpdatedSettingsWithoutChangingPhone() {
        var windowValue = -1f
        val brightness = ReaderWindowBrightness({ windowValue }, { windowValue = it })
        brightness.update(ReaderLightingSettings(usePhoneBrightness = false, brightnessPercent = 30))
        assertEquals(-1f, windowValue, 0f)
        brightness.setForeground(true)
        assertEquals(.3f, windowValue, .0001f)
        brightness.setForeground(false)
        assertEquals(-1f, windowValue, 0f)
        brightness.update(ReaderLightingSettings(usePhoneBrightness = false, brightnessPercent = 70))
        assertEquals(-1f, windowValue, 0f)
        brightness.setForeground(true)
        assertEquals(.7f, windowValue, .0001f)
        brightness.update(ReaderLightingSettings())
        assertEquals(-1f, windowValue, 0f)
        brightness.close()
        assertEquals(-1f, windowValue, 0f)
    }
}
