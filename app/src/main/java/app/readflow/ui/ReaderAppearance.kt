package app.readflow.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.readflow.data.Preferences
import app.readflow.data.ReaderAppearancePreferences
import kotlin.math.roundToInt

internal data class ReaderPaper(
    val id: String,
    val label: String,
    val description: String,
    val paper: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val chrome: Color,
    val inset: Color,
    val outline: Color,
    val sentence: Color,
    val dark: Boolean = false,
    val grain: Boolean = true,
) {
    fun colorScheme(): ColorScheme {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = accent, onPrimary = onAccent, primaryContainer = sentence, onPrimaryContainer = ink,
            secondary = muted, onSecondary = chrome, secondaryContainer = inset, onSecondaryContainer = ink,
            tertiary = accent, onTertiary = onAccent, tertiaryContainer = sentence, onTertiaryContainer = ink,
            background = paper, onBackground = ink, surface = chrome, onSurface = ink,
            surfaceVariant = inset, onSurfaceVariant = muted, outline = muted, outlineVariant = outline,
            surfaceTint = Color.Transparent, surfaceContainerLowest = paper, surfaceContainerLow = chrome,
            surfaceContainer = chrome, surfaceContainerHigh = inset, surfaceContainerHighest = inset,
            inverseSurface = ink, inverseOnSurface = paper, inversePrimary = accent,
        )
    }
}

internal object ReaderPapers {
    val options = listOf(
        ReaderPaper(ReaderAppearancePreferences.WARM, "Warm Paperback", "Cream paper · teal accents",
            paper = Color(0xFFF6EEDC), ink = Color(0xFF2E302B), muted = Color(0xFF62675C),
            accent = Color(0xFF246A5E), onAccent = Color(0xFFFFFFFF), chrome = Color(0xFFF9F4E8),
            inset = Color(0xFFECE5D5), outline = Color(0xFFD8D1BF), sentence = Color(0xFFD9E7D9)),
        ReaderPaper(ReaderAppearancePreferences.CRISP, "Crisp Paper", "Clean paper · slate blue",
            paper = Color(0xFFFCFCFA), ink = Color(0xFF282E36), muted = Color(0xFF596572),
            accent = Color(0xFF405F7D), onAccent = Color(0xFFFFFFFF), chrome = Color(0xFFFFFFFF),
            inset = Color(0xFFF0F2F3), outline = Color(0xFFDCE1E5), sentence = Color(0xFFE0E9F1), grain = false),
        ReaderPaper(ReaderAppearancePreferences.EVENING, "Evening Paper", "Soft ivory · warm charcoal",
            paper = Color(0xFF242321), ink = Color(0xFFF0E8D9), muted = Color(0xFFBDB5A7),
            accent = Color(0xFFE7BB79), onAccent = Color(0xFF302719), chrome = Color(0xFF2B2926),
            inset = Color(0xFF37332D), outline = Color(0xFF4C473E), sentence = Color(0xFF494235), dark = true),
    )

    fun selected(id: String): ReaderPaper = options.firstOrNull { it.id == id } ?: options.first()
}

internal val LocalReaderPaper = staticCompositionLocalOf { ReaderPapers.options.first() }

@Composable internal fun ReaderSystemBarAppearance(dark: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, dark) {
        if (!view.isInEditMode) {
            var context: Context = view.context
            while (context is ContextWrapper && context !is Activity) context = context.baseContext
            (context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        onDispose { }
    }
}

@Composable internal fun ReaderTheme(theme: String, content: @Composable () -> Unit) {
    val paper = remember(theme) { ReaderPapers.selected(theme) }
    val colors = remember(paper) { paper.colorScheme() }
    CompositionLocalProvider(LocalReaderPaper provides paper) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

/** A stationary, cached pattern underneath actual text; it never touches text layout or hit targets. */
@Composable internal fun Modifier.readerPaper(paper: ReaderPaper): Modifier = then(remember(paper) { Modifier.drawWithCache {
    val grain = Path()
    if (paper.grain) {
        val spacing = 13.dp.toPx()
        val radius = .38.dp.toPx()
        var row = 0
        var y = spacing / 2
        while (y < size.height) {
            var x = spacing / 2 + (row % 3) * spacing / 4
            while (x < size.width) {
                grain.addOval(Rect(x - radius, y - radius, x + radius, y + radius))
                x += spacing
            }
            row++
            y += spacing
        }
    }
    onDrawBehind {
        drawRect(paper.paper)
        if (paper.grain) drawPath(grain, paper.ink.copy(alpha = if (paper.dark) .035f else .045f))
    }
} })

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ReaderAppearanceSheet(prefs: Preferences, update: (Preferences) -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your reading space", style = MaterialTheme.typography.titleLarge)
                    Text("Paper, type and a size that suits you.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = dismiss) { Text("Done") }
            }
            ReaderAppearanceControls(prefs, update)
        }
    }
}

@Composable internal fun ReaderAppearanceControls(prefs: Preferences, update: (Preferences) -> Unit) {
    var readerFontSize by remember(prefs.fontSize) { mutableIntStateOf(prefs.fontSize) }
    val paper = ReaderPapers.selected(prefs.readerTheme)
    val font = ReaderFonts.family(prefs.readerFont)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Paper", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderPapers.options.forEach { option ->
                val selected = option.id == prefs.readerTheme
                Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = option.paper,
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) option.accent else option.outline)) {
                    Column(Modifier.selectable(selected = selected, role = Role.RadioButton,
                        onClick = { update(prefs.copy(readerTheme = option.id)) })
                        .semantics { contentDescription = option.label + ". " + option.description }
                        .padding(horizontal = 10.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Aa", fontFamily = FontFamily.Serif, fontSize = 26.sp, color = option.ink, modifier = Modifier.weight(1f))
                            if (selected) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = option.accent)
                        }
                        Text(option.label, color = option.ink, style = MaterialTheme.typography.labelMedium, minLines = 2)
                        Box(Modifier.width(28.dp).height(3.dp).background(option.accent, RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
        Text("Reader font", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        ReaderFontSelector(prefs.readerFont) { update(prefs.copy(readerFont = it)) }
        Text("Font size · $readerFontSize", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        Slider(readerFontSize.toFloat(), { readerFontSize = it.roundToInt() }, valueRange = 5f..34f, steps = 28,
            modifier = Modifier.semantics { contentDescription = "Reader font size" })
        Surface(shape = RoundedCornerShape(16.dp), color = paper.paper, border = BorderStroke(1.dp, paper.outline)) {
            Column(Modifier.readerPaper(paper).fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A little more time to read.", fontFamily = font, fontSize = readerFontSize.sp,
                    lineHeight = (readerFontSize * 1.65f).sp, color = paper.ink)
                Text("Choose the page that feels right for you.", fontFamily = font, fontSize = readerFontSize.sp,
                    lineHeight = (readerFontSize * 1.65f).sp, color = paper.ink)
            }
        }
        FilledTonalButton(onClick = { update(prefs.copy(fontSize = readerFontSize)) },
            enabled = readerFontSize != prefs.fontSize, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (readerFontSize == prefs.fontSize) "Your default size · ${prefs.fontSize}" else "Save as my default")
        }
        Text("Paper and font are saved as you choose. Your default size is used across documents and app restarts. Fonts and size apply to text view; original PDF pages keep their layout.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ReaderFontSelector(selected: String, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = ReaderFonts.options.firstOrNull { it.id == selected } ?: ReaderFonts.options.first()
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
            Text(current.label, fontFamily = current.family, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, "Choose reader font")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ReaderFonts.options.forEach { option ->
                DropdownMenuItem(text = { Text(option.label, fontFamily = option.family, fontSize = 16.sp,
                    fontWeight = if (option.id == current.id) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { choose(option.id); expanded = false },
                    trailingIcon = { if (option.id == current.id) Icon(Icons.Default.Check, "Selected") })
            }
        }
    }
}
