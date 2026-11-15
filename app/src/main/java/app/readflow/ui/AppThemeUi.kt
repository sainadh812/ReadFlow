package app.readflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.readflow.data.AppTheme

/** App chrome has its own explicit theme; document paper and lighting stay independent. */
@Composable internal fun AppThemeAppearance(theme: AppTheme, content: @Composable () -> Unit) {
    val serif = theme != AppTheme.MODERN_FOCUS
    val heading = if (serif) FontFamily.Serif else FontFamily.SansSerif
    MaterialTheme(
        colorScheme = appThemeColors(theme),
        typography = Typography(
            displaySmall = TextStyle(fontFamily = heading, fontSize = 38.sp, lineHeight = 44.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp),
            headlineLarge = TextStyle(fontFamily = heading, fontSize = 34.sp, lineHeight = 41.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-.6).sp),
            headlineMedium = TextStyle(fontFamily = heading, fontSize = 29.sp, lineHeight = 35.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-.4).sp),
            headlineSmall = TextStyle(fontFamily = heading, fontSize = 25.sp, lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontFamily = heading, fontSize = 22.sp, lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 28.sp),
            bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
            labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        ),
        shapes = Shapes(
            small = RoundedCornerShape(if (serif) 12.dp else 10.dp),
            medium = RoundedCornerShape(if (serif) 20.dp else 16.dp),
            large = RoundedCornerShape(if (serif) 26.dp else 20.dp),
        ),
        content = content,
    )
}

internal fun appThemeColors(theme: AppTheme): ColorScheme = when (theme) {
    AppTheme.WARM_LIBRARY -> lightColorScheme(
        primary = Color(0xFF214F3A), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE2E6D7), onPrimaryContainer = Color(0xFF173D2D),
        secondary = Color(0xFF5C6850), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE7E8DA), onSecondaryContainer = Color(0xFF35432C),
        background = Color(0xFFF8F4EC), onBackground = Color(0xFF192B22),
        surface = Color(0xFFFFFCF5), onSurface = Color(0xFF192B22),
        surfaceVariant = Color(0xFFEAE5DB), onSurfaceVariant = Color(0xFF5E6258),
        outline = Color(0xFF858779), outlineVariant = Color(0xFFD8D4C7),
        surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF6F1E8),
        surfaceContainer = Color(0xFFF0EBE1), surfaceContainerHigh = Color(0xFFEAE5DB),
        surfaceContainerHighest = Color(0xFFE3DED3),
    )
    AppTheme.MODERN_FOCUS -> lightColorScheme(
        primary = Color(0xFF214BD4), onPrimary = Color.White,
        primaryContainer = Color(0xFFDFE9FF), onPrimaryContainer = Color(0xFF132C6E),
        secondary = Color(0xFF53627D), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE5EAF4), onSecondaryContainer = Color(0xFF263550),
        background = Color(0xFFF7F9FD), onBackground = Color(0xFF111A33),
        surface = Color.White, onSurface = Color(0xFF111A33),
        surfaceVariant = Color(0xFFE9EDF5), onSurfaceVariant = Color(0xFF535E74),
        outline = Color(0xFF7D879B), outlineVariant = Color(0xFFD4DBE8),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F7FC),
        surfaceContainer = Color(0xFFEEF2F9), surfaceContainerHigh = Color(0xFFE8EDF6),
        surfaceContainerHighest = Color(0xFFE1E7F1),
    )
    AppTheme.NIGHT_READER -> darkColorScheme(
        primary = Color(0xFFB7D99A), onPrimary = Color(0xFF18301A),
        primaryContainer = Color(0xFF263C2B), onPrimaryContainer = Color(0xFFDAEDC8),
        secondary = Color(0xFFB8C7A8), onSecondary = Color(0xFF22301E),
        secondaryContainer = Color(0xFF303E2D), onSecondaryContainer = Color(0xFFD9E8CC),
        background = Color(0xFF101914), onBackground = Color(0xFFF3EFDF),
        surface = Color(0xFF1B271F), onSurface = Color(0xFFF3EFDF),
        surfaceVariant = Color(0xFF2D3C2F), onSurfaceVariant = Color(0xFFBBC5B4),
        outline = Color(0xFF89977F), outlineVariant = Color(0xFF3C4B39),
        surfaceContainerLowest = Color(0xFF0B120E), surfaceContainerLow = Color(0xFF17221B),
        surfaceContainer = Color(0xFF1D2B21), surfaceContainerHigh = Color(0xFF26352A),
        surfaceContainerHighest = Color(0xFF304032),
    )
}

@Composable internal fun ThemeChooser(selected: String, onSelect: (String) -> Unit) {
    val current = AppTheme.fromId(selected)
    val largeType = LocalDensity.current.fontScale >= 1.5f
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppTheme.entries.forEach { theme ->
            val chosen = theme == current
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(if (chosen) 2.dp else 1.dp,
                    if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().selectable(selected = chosen, role = Role.RadioButton,
                    onClick = { onSelect(theme.id) }),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (largeType) AppThemePreview(theme, Modifier.fillMaxWidth().height(100.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (!largeType) AppThemePreview(theme, Modifier.size(width = 100.dp, height = 100.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(theme.label, style = MaterialTheme.typography.titleMedium)
                            Text(when (theme) {
                                AppTheme.WARM_LIBRARY -> "Warm paper and a spacious bookshelf"
                                AppTheme.MODERN_FOCUS -> "Bright, compact document lists"
                                AppTheme.NIGHT_READER -> "Dark surfaces with listening up front"
                            }, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (theme == AppTheme.WARM_LIBRARY) Text("Default", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                        Icon(if (chosen) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null, modifier = Modifier.size(26.dp),
                            tint = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Small native previews use the same palette and layout family as the real screens. */
@Composable private fun AppThemePreview(theme: AppTheme, modifier: Modifier) {
    val colors = appThemeColors(theme)
    Surface(modifier, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, colors.outlineVariant)) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            drawRect(colors.background)
            val x = size.width * .1f
            val width = size.width * .8f
            fun block(color: Color, left: Float, top: Float, w: Float, h: Float) =
                drawRoundRect(color, Offset(left, top), Size(w, h), CornerRadius(size.width * .025f))
            block(colors.onBackground, x, size.height * .12f, width * .54f, size.height * .055f)
            block(colors.surfaceVariant, x, size.height * .23f, width, size.height * .08f)
            val heroHeight = if (theme == AppTheme.MODERN_FOCUS) .2f else .3f
            block(colors.primaryContainer, x, size.height * .37f, width, size.height * heroHeight)
            block(colors.primary, x + width * .07f, size.height * .41f, width * .2f, size.height * (heroHeight - .08f))
            block(colors.onPrimaryContainer.copy(alpha = .7f), x + width * .34f, size.height * .42f, width * .5f, size.height * .035f)
            block(colors.onPrimaryContainer.copy(alpha = .3f), x + width * .34f, size.height * .49f, width * .35f, size.height * .025f)
            if (theme == AppTheme.MODERN_FOCUS) {
                repeat(3) { row ->
                    val y = size.height * (.63f + row * .105f)
                    block(colors.secondaryContainer, x, y, width * .16f, size.height * .075f)
                    block(colors.onSurfaceVariant.copy(alpha = .55f), x + width * .24f, y + size.height * .02f, width * .64f, size.height * .025f)
                }
            } else repeat(2) { column ->
                block(if (column == 0) colors.secondaryContainer else colors.primary.copy(alpha = .35f),
                    x + column * width * .55f, size.height * .74f, width * .45f, size.height * .19f)
            }
        }
    }
}
