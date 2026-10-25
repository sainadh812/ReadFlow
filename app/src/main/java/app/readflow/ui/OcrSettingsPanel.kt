package app.readflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readflow.data.Preferences
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun OcrSettingsScreen(vm: ReaderViewModel, prefs: Preferences) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Text recognition") }, navigationIcon = {
            Tool(Icons.AutoMirrored.Filled.ArrowBack, "Back") { vm.screen.value = if (vm.playback.value.document == null) "settings" else "reader" }
        })
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
            item { OcrSettingsPanel(vm, prefs) }
        }
    }
}

@Composable internal fun OcrSettingsPanel(vm: ReaderViewModel, prefs: Preferences) {
    val downloads by vm.ocrDownloads.collectAsStateWithLifecycle()
    val progress by vm.ocrProgress.collectAsStateWithLifecycle()
    val lastRun by vm.ocrLastRun.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val refreshing by vm.refreshingText.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Choose how ReadFlow sees the page.", Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            style = MaterialTheme.typography.headlineSmall)
        SettingsCard("Recognition model", Icons.Default.DocumentScanner) {
            Text("All three process pages locally. Choose a model, then compare it in the phone testbench.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OcrChoice("ML Kit · Original", "Built in · fast · offline", prefs.ocrEngine == "mlkit", true) { vm.chooseOcrEngine("mlkit") }
            listOf("paddleocr", "teleocr").forEach { id ->
                val setup = downloads[id]
                val installed = remember(setup, id) { vm.app.ocrModels.installed(id) }
                val pack = vm.app.ocrModels.packs.first { it.id == id }
                val name = modelDisplayName(id)
                val size = String.format(Locale.US, "%.1f MB", pack.installedBytes / 1_000_000.0)
                val description = if (id == "paddleocr") "Mobile Latin text recognition · $size" else "Experimental text and tables · $size"
                OcrChoice(name, description, prefs.ocrEngine == id, installed) { vm.chooseOcrEngine(id) }
                if (setup?.busy == true) {
                    Text("$name · ${setup.phase}", style = MaterialTheme.typography.bodySmall)
                    if (setup.total > 0) {
                        LinearProgressIndicator(progress = { (setup.received.toFloat() / setup.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text(String.format(Locale.US, "%.1f / %.1f MB", setup.received / 1_000_000.0, setup.total / 1_000_000.0), style = MaterialTheme.typography.bodySmall)
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { vm.cancelOcrDownload(id) }) { Text("Cancel $name download") }
                } else if (!installed) {
                    setup?.let { Text(it.phase, style = MaterialTheme.typography.bodySmall) }
                    FilledTonalButton(onClick = { vm.downloadOcr(id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Download, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (setup?.canResume == true) "Resume $name download" else "Download $name")
                    }
                } else {
                    TextButton(onClick = { vm.deleteOcr(id) }) { Text("Remove $name download") }
                }
                if (id == "paddleocr") HorizontalDivider()
            }
            Text("PaddleOCR reads text; it has no dedicated table-structure model. TeleOCR can take several minutes per page: prepare difficult pages before listening. Some recognized words may lack exact highlights on the original page.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("TeleOCR needs about 3 GB of free storage during setup. Keep ReadFlow open; interrupted downloads can resume.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsCard("Recognition detail", Icons.Default.Tune) {
            listOf(
                Triple("fast", "Low · Fast", "Less image detail and no focused retries."),
                Triple("balanced", "Medium · Balanced", "Detailed scans and retries for uncertain lines."),
                Triple("high", "High · Accuracy", "More retries and TeleOCR image detail. Takes longer and uses more battery."),
            ).forEach { (id, title, description) ->
                OcrChoice(title, description, prefs.ocrQuality == id, true) { vm.update(prefs.copy(ocrQuality = id)) }
            }
            Text("This changes text recognition, independently of speech buffering.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsCard("Use your selection", Icons.Default.Description) {
            Text("New pages use your selected model. Saved pages keep their text and bookmarks until you apply recognition again. Reliable embedded PDF text is used when available.",
                style = MaterialTheme.typography.bodySmall)
            val canRetry = playback.document?.let { it.mime == "application/pdf" || it.mime.startsWith("image/") } == true
            if (canRetry) {
                Button(onClick = vm::retryOcr, enabled = !refreshing && !playback.preparing && progress == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Apply to current page") }
            }
            if (progress != null || refreshing) {
                Text(progress ?: "Updating text recognition…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = vm::cancelOcr) { Text("Cancel text recognition") }
            }
            lastRun?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = { vm.screen.value = "benchmark" }) {
                Icon(Icons.Default.Science, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Compare in phone testbench")
            }
        }
    }
}

@Composable private fun OcrChoice(title: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .45f) else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick = null, enabled = enabled)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(description + if (!enabled) " · Download to enable" else "", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun ocrQualityLabel(value: String): String = when (value) {
    "fast" -> "Low detail"
    "high" -> "High accuracy"
    else -> "Balanced"
}
