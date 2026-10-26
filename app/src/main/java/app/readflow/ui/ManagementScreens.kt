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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readflow.data.Preferences
import app.readflow.speech.SpeechDistribution
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
                        if (pack.id == "kokoro") {
                            Text("English pronunciation", style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 12.dp))
                            (if (SpeechDistribution.IS_PLAY_BUILD) emptyList() else
                                listOf("espeak" to "Current · eSpeak", "misaki" to "Misaki · experimental")).forEach { (value, label) ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = prefs.kokoroPhonemizer == value,
                                        onClick = { vm.chooseKokoroPhonemizer(value) },
                                        modifier = Modifier.semantics { contentDescription = "Kokoro pronunciation: $label" })
                                    Text(label, Modifier.weight(1f))
                                }
                            }
                            Text(if (SpeechDistribution.IS_PLAY_BUILD) "Misaki prepares English pronunciation for your Kokoro voice. Preview a voice or measure speech preparation in the phone testbench." else "Misaki uses your downloaded Kokoro voice. No extra download is needed. Names, emphasis and rhythm may sound different; preview a voice or compare both in the phone testbench.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(enabled = vm.app.models.installed("alignment"), onClick = {
                                val voice = pack.voices.firstOrNull { it.id == prefs.voice }?.id ?: pack.voices.first().id
                                vm.sample("kokoro", voice, pronunciation = true)
                            }) { Icon(Icons.Default.PlayArrow, null); Text("Try pronunciation sample") }
                        }
                        pack.voices.forEach { voice ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = prefs.model == pack.id && prefs.voice == voice.id, onClick = { vm.chooseModel(pack.id, voice.id) }, modifier = Modifier.semantics { contentDescription = "Use ${voice.name}" })
                                Text(voice.name, Modifier.weight(1f))
                                IconButton(onClick = { vm.sample(pack.id, voice.id) }, enabled = vm.app.models.installed("alignment")) { Icon(Icons.Default.PlayArrow, "Preview ${voice.name}") }
                            }
                        }
                        TextButton(onClick = { vm.deleteModel(pack.id) }) { Icon(Icons.Default.DeleteOutline, null); Spacer(Modifier.width(6.dp)); Text("Delete pack") }
                    }
                    Text(if (pack.id == "alignment") "Required for word synchronization." else if (SpeechDistribution.IS_PLAY_BUILD) "On-device English speech" else "Experimental Android support", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var appearance by remember { mutableStateOf(false) }
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
                    Text("Speech processing effort", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Triple("Low", 1, 1), Triple("Medium", 2, 2), Triple("High", 4, 2)).forEach { (label, speech, alignment) ->
                            FilterChip(selected = prefs.speechThreads == speech && prefs.alignmentThreads == alignment,
                                onClick = { vm.updateSpeechPerformance(prefs.copy(speechThreads = speech, alignmentThreads = alignment)) },
                                label = { Text(label) }, modifier = Modifier.weight(1f))
                        }
                    }
                    Text("Uses ${prefs.speechThreads} speech and ${prefs.alignmentThreads} timing threads. More threads may finish faster but can increase heat. Voice quality stays the same; compare sustained results on your phone.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingsSwitch("Keep CPU workers active", "Experimental: may reduce brief waits, with higher battery use. Off is recommended for long reading.", prefs.allowSpinning) {
                        vm.updateSpeechPerformance(prefs.copy(allowSpinning = it))
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
                    SettingsSwitch("App dark mode", "For your library and settings. Reader paper has its own choice.", prefs.dark) { vm.update(prefs.copy(dark = it)) }
                    val paper = ReaderPapers.selected(prefs.readerTheme)
                    val font = ReaderFonts.options.firstOrNull { it.id == prefs.readerFont } ?: ReaderFonts.options.first()
                    Surface(color = paper.paper, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, paper.outline)) {
                        Column(Modifier.fillMaxWidth().readerPaper(paper).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("A little more time to read.", color = paper.ink, fontFamily = font.family,
                                style = MaterialTheme.typography.bodyLarge)
                            Text("${paper.label} · ${font.label} · ${prefs.fontSize}", color = paper.muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    FilledTonalButton(onClick = { appearance = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.TextFields, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Paper, font and size")
                    }
                }
            }
            item {
                SettingsCard("Pages and reading", Icons.Default.AutoStories) {
                    SettingsSwitch("Swipe to turn pages", "Swipe sideways to change page. In the original view, pinch to zoom.", prefs.swipePages) { vm.update(prefs.copy(swipePages = it)) }
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
            item { SettingsDestination(Icons.Default.Info, "Privacy and licenses", "How data is handled and third-party acknowledgments") { vm.screen.value = "legal" } }
            item { Text("ReadFlow ${app.readflow.BuildConfig.VERSION_NAME} · Android 15+\nDocument processing and speech stay on this device.",
                Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (appearance) ReaderTheme(prefs.readerTheme) { ReaderAppearanceSheet(prefs, vm::update) { appearance = false } }
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
