package app.readflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readflow.data.Preferences
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun Voices(vm: ReaderViewModel) {
    val progress by vm.downloads.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Voice models") }, navigationIcon = { Tool(Icons.AutoMirrored.Filled.ArrowBack, "Back") { vm.screen.value = if (vm.playback.value.document == null) "library" else "reader" } })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(vm.app.models.packs, key = { it.id }) { pack ->
                val status = progress[pack.id]
                val installed = remember(status, pack.id) { vm.app.models.installed(pack.id) }
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (pack.id == "alignment") Icons.Default.Sync else Icons.Default.RecordVoiceOver, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pack.name, style = MaterialTheme.typography.titleMedium)
                            Text("${pack.version} · ${String.format(Locale.US, "%.1f MB", (pack.downloadBytes + pack.extras.sumOf { it.bytes }) / 1_000_000.0)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Tool(Icons.Default.Info, "${pack.name} details") { details = pack.id }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(if (installed) "Installed" else status?.phase ?: "Not installed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    if (status?.busy == true) {
                        if (status.total > 0) LinearProgressIndicator(progress = { (status.received.toFloat() / status.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        if (status.total > 0) Text(
                            String.format(Locale.US, "%d%% · %.1f / %.1f MB", (status.received * 100 / status.total).coerceIn(0, 100), status.received / 1_000_000.0, status.total / 1_000_000.0),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { vm.cancelDownload(pack.id) }) { Icon(Icons.Default.Close, null); Text("Cancel setup") }
                    } else if (!installed) Button(onClick = { vm.download(pack.id) }) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text(if (status?.canResume == true) "Resume setup" else "Download") }
                    else {
                        pack.voices.forEach { voice ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = prefs.model == pack.id && prefs.voice == voice.id, onClick = { vm.chooseModel(pack.id, voice.id) }, modifier = Modifier.semantics { contentDescription = "Use ${voice.name}" })
                                Text(voice.name, Modifier.weight(1f))
                                IconButton(onClick = { vm.sample(pack.id, voice.id) }, enabled = vm.app.models.installed("alignment")) { Icon(Icons.Default.PlayArrow, "Preview ${voice.name}") }
                            }
                        }
                        TextButton(onClick = { vm.deleteModel(pack.id) }) { Icon(Icons.Default.DeleteOutline, null); Spacer(Modifier.width(6.dp)); Text("Delete pack") }
                    }
                    Text(if (pack.id == "alignment") "Required for word synchronization with either voice model." else "Experimental Android support", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
    details?.let { id ->
        val pack = vm.app.models.packs.first { it.id == id }
        AlertDialog(onDismissRequest = { details = null }, title = { Text(pack.name) }, text = { Text("${pack.runtime}\n\nInstalled size: ${String.format(Locale.US, "%.1f MB", pack.installedBytes / 1_000_000.0)}\n\n${pack.licenses.joinToString("\n")}\n\n${pack.support}") }, confirmButton = { TextButton(onClick = { details = null }) { Text("Close") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun Settings(vm: ReaderViewModel, prefs: Preferences) {
    val issues by vm.issueReports.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Make it yours") }, navigationIcon = { Tool(Icons.AutoMirrored.Filled.ArrowBack, "Back") { vm.screen.value = if (vm.playback.value.document == null) "library" else "reader" } })
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("SETTINGS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            }
            item { SettingsDestination(Icons.Default.DocumentScanner, "Text recognition", "${modelDisplayName(prefs.ocrEngine)} · ${ocrQualityLabel(prefs.ocrQuality)}") { vm.screen.value = "ocr" } }
            item { SettingsDestination(Icons.Default.RecordVoiceOver, "Voice models", "Choose a voice and manage downloaded models") { vm.screen.value = "voices" } }
            item {
                SettingsCard("Performance", Icons.Default.Speed) {
                    SettingsSwitch("Show reading metrics", "Speech speed, prepared audio and buffering gaps while you read.", prefs.showPerformance) {
                        vm.update(prefs.copy(showPerformance = it))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    FilledTonalButton(onClick = { vm.screen.value = "benchmark" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Science, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Open phone testbench")
                    }
                    Text("Measure OCR, speech and word timing with repeatable samples on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SettingsCard("Appearance", Icons.Default.Palette) {
                    SettingsSwitch("Dark mode", "A softer view for evening reading.", prefs.dark) { vm.update(prefs.copy(dark = it)) }
                    Text("Reader font size · ${prefs.fontSize}", style = MaterialTheme.typography.bodyMedium)
                    Slider(prefs.fontSize.toFloat(), { vm.update(prefs.copy(fontSize = it.toInt())) }, valueRange = 16f..34f, steps = 17, modifier = Modifier.semantics { contentDescription = "Reader font size" })
                    Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(12.dp)) {
                        Text("A little more time to read.", Modifier.fillMaxWidth().padding(16.dp), fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            fontSize = prefs.fontSize.sp)
                    }
                }
            }
            item {
                SettingsCard("Pages and reading", Icons.Default.AutoStories) {
                    SettingsSwitch("Swipe to turn PDF pages", "At normal zoom, swipe sideways to change page. Pinch to zoom.", prefs.swipePages) { vm.update(prefs.copy(swipePages = it)) }
                    SettingsSwitch("Swipe right for next page", if (prefs.swipeRightAdvances) "Right: next · Left: previous" else "Left: next · Right: previous",
                        prefs.swipeRightAdvances, enabled = prefs.swipePages) { vm.update(prefs.copy(swipeRightAdvances = it)) }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    SettingsSwitch("Skip page margins", "Skip detected headers and footers in speech. The page remains complete.", prefs.skipMargins) {
                        vm.app.playback.stop(); vm.update(prefs.copy(skipMargins = it))
                    }
                }
            }
            item {
                SettingsCard("Audio and storage", Icons.Default.Headphones) {
                    Text("Audio cache · ${prefs.cacheMb} MB", style = MaterialTheme.typography.bodyMedium)
                    Slider(prefs.cacheMb.toFloat(), { vm.update(prefs.copy(cacheMb = (it / 128).toInt() * 128)) }, valueRange = 128f..2048f, steps = 14, modifier = Modifier.semantics { contentDescription = "Audio cache size" })
                    Text("Saved audio makes repeat listening faster.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { vm.clearCache() }) { Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Clear audio cache") }
                    HorizontalDivider()
                    Text("Highlight output delay · ${prefs.latencyMs} ms", style = MaterialTheme.typography.bodyMedium)
                    Slider(prefs.latencyMs.toFloat(), { vm.update(prefs.copy(latencyMs = (it / 10).toInt() * 10)) }, valueRange = 0f..500f, steps = 49, modifier = Modifier.semantics { contentDescription = "Highlight audio output delay" })
                    Text("Adjust word highlighting to your headphones. Bluetooth delay varies; it is not measured automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SettingsCard("Help and diagnostics", Icons.Default.HelpOutline) {
                    TextButton(onClick = vm::showIssues) { Icon(Icons.Default.BugReport, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Issue logs (${issues.size})") }
                    TextButton(onClick = vm::copyPlaybackDiagnostics) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Copy playback diagnostics")
                    }
                }
            }
            item { Text("ReadFlow ${app.readflow.BuildConfig.VERSION_NAME} · Android 15+\nDocument processing and speech stay on this device.",
                Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable internal fun SettingsCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable internal fun SettingsSwitch(title: String, description: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, Modifier.semantics { contentDescription = title }, enabled = enabled)
    }
}

@Composable internal fun SettingsDestination(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
