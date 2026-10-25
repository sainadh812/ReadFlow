package app.readflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readflow.data.Preferences
import java.util.Locale

@Composable internal fun OcrSettingsPanel(vm: ReaderViewModel, prefs: Preferences) {
    val downloads by vm.ocrDownloads.collectAsStateWithLifecycle()
    val progress by vm.ocrProgress.collectAsStateWithLifecycle()
    val lastRun by vm.ocrLastRun.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val refreshing by vm.refreshingText.collectAsStateWithLifecycle()
    val setup = downloads["teleocr"]
    val installed = remember(setup) { vm.app.ocrModels.installed("teleocr") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Text recognition", style = MaterialTheme.typography.titleLarge)
        Text("OCR effort", style = MaterialTheme.typography.titleMedium)
        listOf(
            Triple("fast", "Low · Fast", "Less image detail and no focused retries."),
            Triple("balanced", "Medium · Balanced", "Detailed scans and retries for uncertain lines."),
            Triple("high", "High · Accuracy", "More retries and more TeleOCR image detail. Slower; uses more battery."),
        ).forEach { (id, title, description) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(prefs.ocrQuality == id, { vm.update(prefs.copy(ocrQuality = id)) },
                    Modifier.semantics { contentDescription = title })
                Column { Text(title); Text(description, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text("OCR model", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(prefs.ocrEngine == "mlkit", { vm.update(prefs.copy(ocrEngine = "mlkit")) },
                Modifier.semantics { contentDescription = "Use ML Kit OCR" })
            Column { Text("ML Kit"); Text("Built in · fast · works offline", style = MaterialTheme.typography.bodySmall) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(prefs.ocrEngine == "teleocr", { vm.update(prefs.copy(ocrEngine = "teleocr")) },
                Modifier.semantics { contentDescription = "Use TeleOCR" }, enabled = installed)
            Column { Text("TeleOCR · Experimental"); Text("Local text and table recognition · 1.4 GB download", style = MaterialTheme.typography.bodySmall) }
        }
        Text("TeleOCR may take much longer. Phone speed and accuracy are not yet verified. Tables read row by row; some words may have no highlighting on the original page.",
            style = MaterialTheme.typography.bodySmall)
        if (setup?.busy == true) {
            Text(setup.phase, style = MaterialTheme.typography.bodySmall)
            if (setup.total > 0) {
                LinearProgressIndicator(progress = { (setup.received.toFloat() / setup.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(String.format(Locale.US, "%.1f / %.1f MB", setup.received / 1_000_000.0, setup.total / 1_000_000.0), style = MaterialTheme.typography.bodySmall)
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = vm::cancelTeleOcrDownload) { Text("Cancel download") }
        } else if (!installed) {
            setup?.let { Text(it.phase, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = vm::downloadTeleOcr) { Text(if (setup?.canResume == true) "Resume TeleOCR download" else "Download TeleOCR · 1.4 GB") }
            Text("Allow about 3 GB of free storage during setup. Keep ReadFlow open; an interrupted download can resume.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("TeleOCR installed · works offline", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm::deleteTeleOcr) { Text("Remove TeleOCR download") }
        }
        HorizontalDivider()
        Text("These settings apply to new recognition. Saved pages keep their text and bookmarks. Reliable embedded PDF text is used when available.", style = MaterialTheme.typography.bodySmall)
        val canRetry = playback.document?.let { it.mime == "application/pdf" || it.mime.startsWith("image/") } == true
        if (canRetry) {
            Button(onClick = vm::retryOcr, enabled = !refreshing && !playback.preparing && progress == null) { Text("Apply to current page") }
        }
        if (progress != null || refreshing) {
            Text(progress ?: "Updating text recognition…", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm::cancelOcr) { Text("Cancel text recognition") }
        }
        lastRun?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
