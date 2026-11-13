package app.readflow.ui

import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import app.readflow.data.ReaderLightingSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFailureContrastTest {
    @Test fun failedSentenceTextAndUnderlineRemainReadableAcrossTheWholeWarmLightRange() {
        // The viewport tint covers both the red ink and its opaque pale-red background.
        // Use the production tint and bounds so increasing the warm wash cannot hide a failure.
        for (strength in 0..100) {
            val settings = ReaderLightingSettings(warmLightEnabled = true, warmLightStrength = strength)
            val wash = ReaderWarmLightColor.copy(alpha = settings.warmOverlayAlpha)
            val ink = wash.compositeOver(ReaderFailureInk).luminance()
            val background = wash.compositeOver(ReaderFailureBackground).luminance()
            val ratio = (maxOf(ink, background) + .05f) / (minOf(ink, background) + .05f)
            assertTrue("Failure contrast at $strength% warmth is $ratio:1", ratio >= 4.5f)
        }
    }
}
