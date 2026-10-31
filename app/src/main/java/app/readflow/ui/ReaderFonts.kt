package app.readflow.ui

import androidx.annotation.FontRes
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.readflow.R

data class ReaderFontOption(val id: String, val label: String, val family: FontFamily)

/** Bundled fonts work offline. Their unmodified files and licenses are recorded in assets/licenses/fonts. */
object ReaderFonts {
    val options: List<ReaderFontOption> = listOf(
        ReaderFontOption("system_serif", "System Serif", FontFamily.Serif),
        ReaderFontOption("opendyslexic", "OpenDyslexic", FontFamily(
            Font(R.font.opendyslexic_regular, FontWeight.Normal),
            Font(R.font.opendyslexic_bold, FontWeight.Bold),
        )),
        ReaderFontOption("atkinson", "Atkinson Hyperlegible Next", variableFamily(R.font.atkinson_next)),
        ReaderFontOption("lexend", "Lexend", variableFamily(R.font.lexend)),
        ReaderFontOption("literata", "Literata", variableFamily(R.font.literata)),
        ReaderFontOption("source_serif", "Source Serif 4", variableFamily(R.font.source_serif)),
        ReaderFontOption("noto_sans", "Noto Sans", variableFamily(R.font.noto_sans)),
    )

    fun family(id: String): FontFamily = options.firstOrNull { it.id == id }?.family ?: FontFamily.Serif

    // Set the actual variable axis as well as Compose's matching weight, so bold headings use
    // the font's own outlines. Other axes keep their upstream defaults (optical size/normal width).
    @OptIn(ExperimentalTextApi::class)
    private fun variableFamily(@FontRes resource: Int): FontFamily = FontFamily(
        Font(resource, weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(resource, weight = FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(resource, weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600))),
        Font(resource, weight = FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    )
}
