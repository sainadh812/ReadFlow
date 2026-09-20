package app.readflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { Tool(Icons.AutoMirrored.Filled.ArrowBack, "Back") { vm.screen.value = if (vm.playback.value.document == null) "library" else "reader" } })
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Dark mode", Modifier.weight(1f)); Switch(prefs.dark, { vm.update(prefs.copy(dark = it)) }, Modifier.semantics { contentDescription = "Dark mode" }) } }
            item {
                Text("Reader font size · ${prefs.fontSize}")
                Slider(prefs.fontSize.toFloat(), { vm.update(prefs.copy(fontSize = it.toInt())) }, valueRange = 16f..34f, steps = 17, modifier = Modifier.semantics { contentDescription = "Reader font size" })
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Skip page margins", Modifier.weight(1f)); Switch(prefs.skipMargins, { vm.app.playback.stop(); vm.update(prefs.copy(skipMargins = it)) }, Modifier.semantics { contentDescription = "Skip page margins" }) }
                Text("Excludes detected top and bottom margin words from speech. Original text remains available.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Text("Audio cache · ${prefs.cacheMb} MB")
                Slider(prefs.cacheMb.toFloat(), { vm.update(prefs.copy(cacheMb = (it / 128).toInt() * 128)) }, valueRange = 128f..2048f, steps = 14, modifier = Modifier.semantics { contentDescription = "Audio cache size" })
                TextButton(onClick = { vm.clearCache() }) { Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("Clear audio cache") }
            }
            item {
                Text("Highlight output delay · ${prefs.latencyMs} ms")
                Slider(prefs.latencyMs.toFloat(), { vm.update(prefs.copy(latencyMs = (it / 10).toInt() * 10)) }, valueRange = 0f..500f, steps = 49, modifier = Modifier.semantics { contentDescription = "Highlight audio output delay" })
                Text("Manual calibration for your audio route. Bluetooth delay varies; automatic latency measurement is not available.", style = MaterialTheme.typography.bodySmall)
            }
            item { Text("ReadFlow ${app.readflow.BuildConfig.VERSION_NAME} · Android 15+\nDocument processing and speech stay on this device.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
