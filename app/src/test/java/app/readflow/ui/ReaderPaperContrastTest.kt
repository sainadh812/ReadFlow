package app.readflow.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPaperContrastTest {
    @Test fun allPaperThemesKeepTextAndWordHighlightsReadable() {
        ReaderPapers.options.forEach { paper ->
            assertContrast(paper.label + " body", paper.ink, paper.paper, 7f)
            assertContrast(paper.label + " chrome", paper.ink, paper.chrome, 7f)
            assertContrast(paper.label + " secondary", paper.muted, paper.chrome, 4.5f)
            assertContrast(paper.label + " sentence", paper.ink, paper.sentence, 4.5f)
            assertContrast(paper.label + " active word", paper.onAccent, paper.accent, 4.5f)
        }
    }

    private fun assertContrast(label: String, foreground: Color, background: Color, minimum: Float) {
        val a = foreground.luminance(); val b = background.luminance()
        val contrast = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
        assertTrue("$label contrast is $contrast", contrast >= minimum)
    }
}
