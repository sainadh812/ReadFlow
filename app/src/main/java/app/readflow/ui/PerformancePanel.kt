package app.readflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readflow.data.Preferences
import java.util.Locale
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample

/** Kept outside the page so diagnostics never obscure text or intercept word selection. */
@OptIn(FlowPreview::class)
@Composable internal fun PerformancePanel(vm: ReaderViewModel, prefs: Preferences) {
    val initialSummary = remember(vm) { vm.performance.value }
    val summary by remember(vm) { vm.performance.sample(500) }.collectAsStateWithLifecycle(initialValue = initialSummary)
    val extraction by vm.ocrPerformance.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    val usingSavedAudio = summary.recentCacheHits > 0 && summary.synthesisCharactersPerMinute == null
    val noFreshMeasurement = if (usingSavedAudio) "Saved audio · no fresh measurement" else "Waiting for a generated sample"
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .65f), shape = RoundedCornerShape(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 56.dp).clickable(onClickLabel = "Performance details") { expanded = true }
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Speed, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(summary.synthesisCharactersPerMinute?.let { "${metricNumber(it, 0)} chars/min" }
                        ?: if (usingSavedAudio) "Performance · Saved audio" else "Performance · Waiting for audio",
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val buffer = summary.bufferedListeningMs?.let { "${metricSeconds(it)} ready" } ?: "No audio measured yet"
                    val headroom = summary.throughputHeadroom(prefs.speed)?.let { " · ${metricNumber(it)}× listening" }.orEmpty()
                    Text(buffer + headroom, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Tool(Icons.Default.Close, "Hide performance metrics") { vm.update(prefs.copy(showPerformance = false)) }
        }
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text("Reading performance") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Measured on this phone. Speech rates use completed, freshly generated audio; cached audio is excluded.",
                style = MaterialTheme.typography.bodySmall)
            MetricDetail("Speech model", summary.model?.let(::modelDisplayName) ?: "No reading session yet")
            MetricDetail("Speech generation", summary.synthesisCharactersPerMinute?.let { "${metricNumber(it, 0)} chars/min" } ?: noFreshMeasurement)
            MetricDetail("Speech preparation", summary.generatedAudioSecondsPerPreparationSecond?.let { "${metricNumber(it)} audio seconds / second" } ?: noFreshMeasurement)
            summary.throughputHeadroom(prefs.speed)?.let { rate ->
                Surface(color = if (rate >= 1) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp)) {
                    Text("${metricNumber(rate)}× your ${metricNumber(prefs.speed.toDouble())}× listening speed. " +
                        if (rate >= 1) "Recent speech preparation has kept up." else "Recent speech preparation is slower than listening; the buffer may run down.",
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                        color = if (rate >= 1) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Text("Includes speech, word timing, model loading and audio storage. Page extraction is measured separately and can still delay reading. This is a speed measurement, not an accuracy score.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            MetricDetail("Listening buffer", summary.bufferedListeningMs?.let(::metricSeconds) ?: "Not measured")
            MetricDetail("Ready audio chunks", summary.readyChunks?.toString() ?: "Not measured")
            MetricDetail("Buffering gaps", summary.totalUnderruns.toString())
            Text("Gaps count unexpected audio-buffer depletion in this reading session, excluding initial preparation. They do not measure silence inside the voice audio.", style = MaterialTheme.typography.bodySmall)
            MetricDetail("Mean speech generation", summary.meanSynthesisMs?.let { metricSeconds(it.toLong()) } ?: "Not measured")
            MetricDetail("Mean word timing", summary.meanAlignmentMs?.let { metricSeconds(it.toLong()) } ?: "Not measured")
            MetricDetail("Recent cached chunks", "${summary.recentCacheHits} of ${summary.recentPreparations}")
            Text("Rates use up to the latest 64 chunks in this session and can change with temperature or other apps.", style = MaterialTheme.typography.bodySmall)
            extraction?.let { last ->
                HorizontalDivider()
                Text(if (last.cached) "Saved page" else "Latest measured extraction", style = MaterialTheme.typography.titleSmall)
                MetricDetail("Text source", last.engine)
                if (last.cached) Text("Saved text reused · no new OCR run", style = MaterialTheme.typography.bodySmall)
                else {
                    MetricDetail("Extraction time", metricSeconds(last.elapsedMs))
                    MetricDetail("Extraction rate", last.charactersPerMinute?.let { "${metricNumber(it, 0)} chars/min" } ?: "Not measured separately")
                }
                MetricDetail("Characters", last.characters.toString())
            }
        }
    }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("Done") } },
        dismissButton = { TextButton(onClick = { expanded = false; vm.screen.value = "benchmark" }) { Text("Phone testbench") } })
}

@Composable internal fun MetricDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

internal fun metricNumber(value: Double, decimals: Int = 1): String =
    if (value.isFinite()) String.format(Locale.US, "%,.${decimals}f", value) else "Unavailable"
internal fun metricSeconds(milliseconds: Long): String = when {
    milliseconds < 1000 -> "$milliseconds ms"
    milliseconds < 60_000 -> "${metricNumber(milliseconds / 1000.0)} s"
    else -> "${milliseconds / 60000} min ${milliseconds / 1000 % 60} s"
}
internal fun modelDisplayName(id: String): String = when (id) {
    "kokoro" -> "Kokoro"
    "pocket" -> "Pocket TTS"
    "mlkit" -> "ML Kit · Original"
    "teleocr" -> "TeleOCR"
    "paddleocr" -> "PaddleOCR"
    "sc-kokoro" -> "SC Kokoro · external"
    else -> id
}
