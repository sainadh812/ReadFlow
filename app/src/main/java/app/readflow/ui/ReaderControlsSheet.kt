package app.readflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readflow.data.Preferences
import app.readflow.models.PackProgress
import app.readflow.playback.ReaderPlayback
import app.readflow.speech.SpeechDistribution
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Reader-local settings: opening, changing tabs, and managing downloads never navigate away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ReaderControlsSheet(
    vm: ReaderViewModel,
    state: ReaderPlayback,
    prefs: Preferences,
    dismiss: () -> Unit,
    notifications: () -> Unit = {},
) {
    // A partial anchor would place the footer below the screen. Bound the whole sheet instead.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val quickList = rememberLazyListState()
    val advancedList = rememberLazyListState()
    val feedback by vm.message.collectAsStateWithLifecycle()
    var advanced by rememberSaveable { mutableStateOf(false) }
    var roomy by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(if (advanced || roomy) .94f else .88f)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reader controls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Page ${(state.page?.index ?: state.viewPageIndex) + 1} · ${if (advanced) "Advanced" else "Quick settings"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    if (advanced || roomy) { advanced = false; roomy = false } else roomy = true
                }) {
                    Icon(if (advanced || roomy) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        if (advanced || roomy) "Reduce controls panel" else "Expand controls panel")
                }
                IconButton(onClick = dismiss) { Icon(Icons.Default.Close, "Close reader controls") }
            }
            TabRow(selectedTabIndex = if (advanced) 1 else 0, modifier = Modifier.padding(horizontal = 16.dp)) {
                Tab(selected = !advanced, onClick = { advanced = false }, text = { Text("Quick") })
                Tab(selected = advanced, onClick = { advanced = true }, text = { Text("Advanced") })
            }
            if (!advanced) LazyColumn(
                state = quickList, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { ReaderEffortControl(vm, prefs) }
                item { ReaderRecognitionDetail(vm, prefs) }
                item { ReaderVoiceControls(vm, prefs) {
                    advanced = true
                    scope.launch { advancedList.scrollToItem(1) }
                } }
                item { ReaderSpeedControl(vm, prefs) }
            } else LazyColumn(
                state = advancedList, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { ReaderRecognitionModels(vm, state, prefs) }
                item { ReaderVoiceModels(vm, prefs) }
                item { ReaderPerformanceControls(vm, prefs) }
                item { ReaderAudioTuning(vm, prefs) }
            }
            feedback?.let { message ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(message, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
            ReaderSheetTransport(vm, state, notifications)
        }
    }
}

@Composable private fun ReaderControlCard(title: String?, icon: ImageVector, compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 12.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable private fun ReaderEffortControl(vm: ReaderViewModel, prefs: Preferences) {
    ReaderControlCard("Speech processing effort", Icons.Default.Speed, compact = true) {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Triple("Low", 1, 1), Triple("Medium", 2, 2), Triple("High", 4, 2)).forEach { (label, speech, alignment) ->
                val selected = prefs.speechThreads == speech && prefs.alignmentThreads == alignment
                FilterChip(selected = selected, onClick = {
                    if (!selected) vm.updateSpeechPerformance(prefs.copy(speechThreads = speech, alignmentThreads = alignment))
                }, label = { Text(label) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            }
        }
    }
}

@Composable private fun ReaderRecognitionDetail(vm: ReaderViewModel, prefs: Preferences) {
    ReaderControlCard("Text recognition", Icons.Default.DocumentScanner, compact = true) {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("fast" to "Fast", "balanced" to "Balanced", "high" to "High").forEach { (id, label) ->
                FilterChip(selected = prefs.ocrQuality == id, onClick = {
                    if (prefs.ocrQuality != id) vm.update(prefs.copy(ocrQuality = id))
                }, label = { Text(label) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            }
        }
        Text("Apply to saved pages in Advanced.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ReaderVoiceControls(vm: ReaderViewModel, prefs: Preferences, manage: () -> Unit) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    // The build's manifest is the source of truth: unsupported debug voices never appear in release.
    val packs = remember(vm) { vm.app.models.packs.filter { it.voices.isNotEmpty() } }
    val installed = remember(vm, downloads) { vm.app.models.packs.associate { it.id to vm.app.models.installed(it.id) } }
    val current = packs.firstOrNull { it.id == prefs.model }
    var modelsOpen by remember { mutableStateOf(false) }
    var voicesOpen by remember { mutableStateOf(false) }
    val available = current != null && installed[current.id] == true
    val modelChoice: @Composable () -> Unit = {
        ReaderChoiceButton("Voice model", current?.let { modelDisplayName(it.id) } ?: "Choose model",
            expanded = modelsOpen, open = { modelsOpen = true }, close = { modelsOpen = false }) {
            packs.forEach { pack ->
                DropdownMenuItem(text = {
                    Column {
                        Text(modelDisplayName(pack.id))
                        if (installed[pack.id] != true) Text("Download to use", style = MaterialTheme.typography.bodySmall)
                    }
                }, enabled = installed[pack.id] == true, onClick = {
                    if (prefs.model != pack.id) vm.chooseModel(pack.id, pack.voices.first().id)
                    modelsOpen = false
                }, trailingIcon = { if (prefs.model == pack.id) Icon(Icons.Default.Check, "Selected") })
            }
        }
    }
    val voiceChoice: @Composable () -> Unit = {
        ReaderChoiceButton("Voice", current?.voices?.firstOrNull { it.id == prefs.voice }?.name ?: "Choose voice",
            enabled = available, expanded = voicesOpen, open = { voicesOpen = true }, close = { voicesOpen = false }) {
            current?.let { pack ->
                pack.voices.forEach { voice ->
                    DropdownMenuItem(text = { Text(voice.name) }, onClick = {
                        if (prefs.voice != voice.id) vm.chooseModel(pack.id, voice.id)
                        voicesOpen = false
                    }, trailingIcon = { if (prefs.voice == voice.id) Icon(Icons.Default.Check, "Selected") })
                }
            }
        }
    }
    ReaderControlCard(null, Icons.Default.RecordVoiceOver, compact = true) {
        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 280.dp || fontScale > 1.2f) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { modelChoice(); voiceChoice() }
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { modelChoice() }
                Box(Modifier.weight(1f)) { voiceChoice() }
            }
        }
        if (!available) Text("Download this voice model to start listening.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (installed["alignment"] != true) Text("Download word synchronization in voice downloads to start listening.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = manage, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Manage voice downloads")
        }
    }
}

@Composable private fun ReaderChoiceButton(
    label: String, value: String, expanded: Boolean, open: () -> Unit, close: () -> Unit,
    enabled: Boolean = true, menu: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = open, enabled = enabled, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = "$label: $value" },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Default.ExpandMore, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = close, content = menu)
    }
}

@Composable private fun ReaderSpeedControl(vm: ReaderViewModel, prefs: Preferences) {
    var speed by remember(prefs.speed) { mutableFloatStateOf(prefs.speed.coerceIn(.75f, 2.5f)) }
    ReaderControlCard("Reading speed · ${readerSpeedLabel(speed)}", Icons.Default.Headphones, compact = true) {
        Slider(value = speed, onValueChange = { speed = (it * 4f).roundToInt() / 4f }, valueRange = .75f..2.5f, steps = 6,
            onValueChangeFinished = { if (speed != prefs.speed) vm.update(prefs.copy(speed = speed)) },
            modifier = Modifier.semantics { contentDescription = "Reading speed"; stateDescription = readerSpeedLabel(speed) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0.75×", style = MaterialTheme.typography.labelSmall)
            Text("Normal · 1×", style = MaterialTheme.typography.labelSmall)
            Text("2.5×", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun ReaderRecognitionModels(vm: ReaderViewModel, state: ReaderPlayback, prefs: Preferences) {
    val downloads by vm.ocrDownloads.collectAsStateWithLifecycle()
    val progress by vm.ocrProgress.collectAsStateWithLifecycle()
    val lastRun by vm.ocrLastRun.collectAsStateWithLifecycle()
    val refreshing by vm.refreshingText.collectAsStateWithLifecycle()
    ReaderControlCard("Recognition model", Icons.Default.DocumentScanner) {
        Text("Higher recognition detail can improve difficult scans, with more time and battery use. It is separate from speech processing effort.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReaderModelOption("ML Kit · Original", "Built in · fast · offline", prefs.ocrEngine == "mlkit", true) {
            if (prefs.ocrEngine != "mlkit") vm.chooseOcrEngine("mlkit")
        }
        vm.app.ocrModels.packs.forEach { pack ->
            val setup = downloads[pack.id]
            val installed = remember(setup, pack.id) { vm.app.ocrModels.installed(pack.id) }
            HorizontalDivider()
            ReaderModelOption(modelDisplayName(pack.id), "${readerMegabytes(pack.installedBytes)} installed", prefs.ocrEngine == pack.id, installed) {
                if (prefs.ocrEngine != pack.id) vm.chooseOcrEngine(pack.id)
            }
            ReaderDownloadControl(modelDisplayName(pack.id), setup, installed,
                download = { vm.downloadOcr(pack.id) }, cancel = { vm.cancelOcrDownload(pack.id) }, remove = { vm.deleteOcr(pack.id) })
        }
        Text("TeleOCR is experimental and can take several minutes per page. Setup needs about 3 GB free. Keep the app open during downloads.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("New pages use your selected settings. Saved pages keep their text and bookmarks until you apply recognition again. Reliable embedded PDF text is used when available.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val canApply = state.document?.let { it.mime == "application/pdf" || it.mime.startsWith("image/") } == true
        if (canApply) Button(onClick = vm::retryOcr, enabled = !refreshing && !state.preparing && progress == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Apply to current page") }
        if (progress != null || refreshing) {
            Text(progress ?: "Updating text recognition…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = vm::cancelOcr) { Text("Cancel text recognition") }
        }
        lastRun?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable private fun ReaderVoiceModels(vm: ReaderViewModel, prefs: Preferences) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    ReaderControlCard("Voice downloads", Icons.Default.CloudDownload) {
        Text("Models stay on this phone. Voice choices appear in Quick settings after setup finishes.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        vm.app.models.packs.forEachIndexed { index, pack ->
            if (index > 0) HorizontalDivider()
            val setup = downloads[pack.id]
            val installed = remember(setup, pack.id) { vm.app.models.installed(pack.id) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pack.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("${readerMegabytes(pack.downloadBytes + pack.extras.sumOf { it.bytes })} download · ${if (installed) "Installed" else setup?.phase ?: "Not installed"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pack.id == "alignment") Text("Required to synchronize words with speech.", style = MaterialTheme.typography.bodySmall)
                ReaderDownloadControl(pack.name, setup, installed,
                    download = { vm.download(pack.id) }, cancel = { vm.cancelDownload(pack.id) }, remove = { vm.deleteModel(pack.id) })
                if (pack.id == "kokoro" && installed) {
                    if (!SpeechDistribution.IS_PLAY_BUILD) {
                        Text("English pronunciation", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("espeak" to "eSpeak", "misaki" to "Misaki").forEach { (id, label) ->
                                FilterChip(selected = prefs.kokoroPhonemizer == id, onClick = {
                                    if (prefs.kokoroPhonemizer != id) vm.chooseKokoroPhonemizer(id)
                                }, label = { Text(label) })
                            }
                        }
                        Text("Misaki pronunciation is experimental in this build.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Text("English speech with Misaki pronunciation.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun ReaderModelOption(title: String, subtitle: String, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, enabled = enabled,
        role = Role.RadioButton, onClick = choose), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle + if (!enabled) " · Download to use" else "", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ReaderDownloadControl(
    name: String, progress: PackProgress?, installed: Boolean, download: () -> Unit, cancel: () -> Unit, remove: () -> Unit,
) {
    if (progress?.busy == true) {
        Text(progress.phase, style = MaterialTheme.typography.bodySmall)
        if (progress.total > 0) {
            LinearProgressIndicator(progress = { (progress.received.toFloat() / progress.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("${readerMegabytes(progress.received)} / ${readerMegabytes(progress.total)}", style = MaterialTheme.typography.bodySmall)
        } else LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel $name setup") }
    } else if (!installed) {
        progress?.let { Text(it.phase, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FilledTonalButton(onClick = download, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(if (progress?.canResume == true) "Resume $name setup" else "Download $name")
        }
    } else TextButton(onClick = remove, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Remove $name download")
    }
}

@OptIn(FlowPreview::class)
@Composable private fun ReaderPerformanceControls(vm: ReaderViewModel, prefs: Preferences) {
    val initial = remember(vm) { vm.performance.value }
    val summary by remember(vm) { vm.performance.sample(500) }.collectAsStateWithLifecycle(initialValue = initial)
    val extraction by vm.ocrPerformance.collectAsStateWithLifecycle()
    ReaderControlCard("Performance", Icons.Default.Insights) {
        Text("More speech processing effort may prepare audio faster and use more battery; voice quality stays the same. Changing effort or voice pauses playback while your page stays open.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsSwitch("Show reading metrics", "Show speech preparation and audio buffer beside the page.", prefs.showPerformance) {
            vm.update(prefs.copy(showPerformance = it))
        }
        SettingsSwitch("Keep CPU workers active", "May reduce short waits, with higher battery use. Off suits longer reading.", prefs.allowSpinning) {
            if (prefs.allowSpinning != it) vm.updateSpeechPerformance(prefs.copy(allowSpinning = it))
        }
        Text("${prefs.speechThreads} speech threads · ${prefs.alignmentThreads} word-timing threads",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("Measured on this phone", style = MaterialTheme.typography.titleSmall)
        val noFresh = if (summary.recentCacheHits > 0) "Saved audio · no fresh measurement" else "Waiting for a generated sample"
        MetricDetail("Speech generation", summary.synthesisCharactersPerMinute?.let { "${metricNumber(it, 0)} chars/min" } ?: noFresh)
        MetricDetail("Speech preparation", summary.generatedAudioSecondsPerPreparationSecond?.let { "${metricNumber(it)} audio seconds / second" } ?: noFresh)
        MetricDetail("Prepared listening buffer", summary.bufferedListeningMs?.let(::metricSeconds) ?: "Not measured")
        MetricDetail("Buffering gaps", summary.totalUnderruns.toString())
        Text("Generated audio only; saved audio is excluded from rates. Gaps exclude initial preparation. These are speed measurements, not accuracy scores.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        extraction?.let { last ->
            HorizontalDivider()
            MetricDetail("Latest page extraction", last.engine)
            Text(if (last.cached) "Saved text reused · no new recognition run" else "${metricSeconds(last.elapsedMs)} · ${last.characters} characters",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ReaderAudioTuning(vm: ReaderViewModel, prefs: Preferences) {
    var cacheMb by remember(prefs.cacheMb) { mutableIntStateOf(prefs.cacheMb) }
    var latencyMs by remember(prefs.latencyMs) { mutableIntStateOf(prefs.latencyMs) }
    ReaderControlCard("Audio tuning", Icons.Default.Tune) {
        Text("Audio cache · $cacheMb MB", style = MaterialTheme.typography.bodyMedium)
        Slider(cacheMb.toFloat(), { cacheMb = ((it / 128).roundToInt() * 128).coerceIn(128, 2048) },
            valueRange = 128f..2048f, steps = 14,
            onValueChangeFinished = { if (cacheMb != prefs.cacheMb) vm.update(prefs.copy(cacheMb = cacheMb)) },
            modifier = Modifier.semantics { contentDescription = "Audio cache size" })
        TextButton(onClick = vm::clearCache) { Text("Clear saved audio") }
        HorizontalDivider()
        Text("Highlight output delay · $latencyMs ms", style = MaterialTheme.typography.bodyMedium)
        Slider(latencyMs.toFloat(), { latencyMs = ((it / 10).roundToInt() * 10).coerceIn(0, 500) },
            valueRange = 0f..500f, steps = 49,
            onValueChangeFinished = { if (latencyMs != prefs.latencyMs) vm.update(prefs.copy(latencyMs = latencyMs)) },
            modifier = Modifier.semantics { contentDescription = "Highlight audio output delay" })
        Text("Adjust word highlighting for your headphones. Bluetooth delay is not measured automatically.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ReaderSheetTransport(vm: ReaderViewModel, state: ReaderPlayback, notifications: () -> Unit) {
    val preparing = state.fillingBuffer || (state.preparing && state.currentChunk == null)
    val selected = state.selectedWordId != null && !state.wantsToPlay && !state.preparing
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (state.wantsToPlay) "Listening" else if (state.preparing) "Preparing audio" else "Ready to read",
                style = MaterialTheme.typography.labelLarge)
            Text(state.status, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { vm.app.playback.sentence(-1) }, enabled = state.page?.words?.isNotEmpty() == true) {
            Icon(Icons.Default.SkipPrevious, "Previous sentence")
        }
        FilledIconButton(onClick = {
            if (!state.wantsToPlay && !state.preparing) notifications()
            if (selected) vm.app.playback.start(wordId = state.selectedWordId) else vm.app.playback.toggle()
        }, enabled = state.page?.words?.isNotEmpty() == true || state.preparing || state.wantsToPlay, modifier = Modifier.size(52.dp)) {
            Icon(when {
                preparing -> Icons.Default.Close
                state.wantsToPlay -> Icons.Default.Pause
                else -> Icons.Default.PlayArrow
            }, when {
                preparing -> "Cancel audio preparation"
                state.wantsToPlay -> "Pause reading"
                else -> "Start reading"
            }, Modifier.size(28.dp))
        }
        IconButton(onClick = {
            if (state.blockedSentence != null) { notifications(); vm.app.playback.skipBlockedSentence() }
            else vm.app.playback.sentence(1)
        }, enabled = state.page?.words?.isNotEmpty() == true || state.blockedSentence != null) {
            Icon(Icons.Default.SkipNext, if (state.blockedSentence != null) "Skip blocked sentence" else "Next sentence")
        }
    }
}

private fun readerSpeedLabel(value: Float): String = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.') + "×"
private fun readerMegabytes(value: Long): String = String.format(Locale.US, "%.1f MB", value / 1_000_000.0)
