package app.readflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.readflow.speech.ScKokoroEngine
import app.readflow.speech.SpeechDistribution
import app.readflow.speech.ScKokoroAvailability
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readflow.benchmark.*
import app.readflow.data.Preferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun BenchmarkScreen(vm: ReaderViewModel, prefs: Preferences) {
    val state by vm.benchmark.collectAsStateWithLifecycle()
    val busy by vm.benchmarkBusy.collectAsStateWithLifecycle()
    var cancellationRequested by rememberSaveable(state.startedAtMs) { mutableStateOf(false) }
    val active = busy || state.running
    val stopping = active && (cancellationRequested || state.phase.startsWith("Stopping"))
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val ocrDownloads by vm.ocrDownloads.collectAsStateWithLifecycle()
    val alignmentInstalled = remember(downloads) { vm.app.models.installed("alignment") }
    val alignmentAvailable = remember(downloads) { vm.app.models.hasLocalInstallation("alignment") }
    val localSpeech = remember(downloads) {
        SpeechDistribution.benchmarkModels.filter { it != "sc-kokoro" }.filter { vm.app.models.hasLocalInstallation(if (it == "kokoro-misaki") "kokoro" else it) }
    }
    val installedSpeech = remember(downloads) {
        localSpeech.filter { vm.app.models.installed(if (it == "kokoro-misaki") "kokoro" else it) }
    }
    val teleInstalled = remember(ocrDownloads) { vm.app.ocrModels.installed("teleocr") }
    val paddleInstalled = remember(ocrDownloads) { vm.app.ocrModels.installed("paddleocr") }
    fun externalAvailability() = if (SpeechDistribution.IS_PLAY_BUILD) ScKokoroAvailability(false, null, "Unavailable in this build") else ScKokoroEngine.availability(vm.app)
    var external by remember { mutableStateOf(externalAvailability()) }
    LifecycleResumeEffect(Unit) {
        external = externalAvailability()
        onPauseOrDispose { }
    }
    val uriHandler = LocalUriHandler.current
    var speechModels by rememberSaveable { mutableStateOf(listOf(
        if (prefs.model == "kokoro" && prefs.kokoroPhonemizer == "misaki") "kokoro-misaki" else prefs.model
    ).filter { it in localSpeech }) }
    var ocrEngines by rememberSaveable { mutableStateOf(listOf("mlkit")) }
    var repetitions by rememberSaveable { mutableIntStateOf(3) }
    var configuring by rememberSaveable { mutableStateOf(false) }
    var compareThreads by rememberSaveable { mutableStateOf(false) }
    var sustained by rememberSaveable { mutableIntStateOf(0) }
    var pronunciationSamples by rememberSaveable { mutableStateOf(false) }
    val eligibleSpeech = speechModels.filter { (it in localSpeech || it == "sc-kokoro" && external.installed) && alignmentAvailable }
    val eligibleOcr = ocrEngines.filter { it == "mlkit" || it == "teleocr" && teleInstalled || it == "paddleocr" && paddleInstalled }
    if (active) KeepReaderScreenAwake()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Phone testbench") }, navigationIcon = {
            Tool(Icons.AutoMirrored.Filled.ArrowBack, if (stopping) "Back · test is stopping" else if (active) "Cancel test and go back" else "Back", vm::leaveBenchmark)
        }, actions = {
            if (state.rows.isNotEmpty()) Tool(Icons.Default.ContentCopy, "Copy benchmark results", vm::copyBenchmarkResults)
        })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Science, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Meet your phone’s pace.", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Real OCR, speech generation and word timing on repeatable samples. Compare speed, sample text errors and device conditions.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Local tests · Your books stay untouched", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            if (!active && state.rows.isNotEmpty()) item {
                OutlinedButton(onClick = { configuring = !configuring }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(if (configuring) Icons.Default.ExpandLess else Icons.Default.Tune, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text(if (configuring) "Hide test options" else "Configure another test")
                }
            }
            if (!active && (state.rows.isEmpty() || configuring)) {
                item {
                    SettingsCard("Choose what to measure", Icons.Default.Tune) {
                        Text("SPEECH + WORD TIMING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        SpeechDistribution.benchmarkModels.filter { it != "sc-kokoro" }.forEach { model ->
                            val available = model in localSpeech
                            BenchmarkChoice(modelDisplayName(model), when {
                                !available -> "Download this voice model to test"
                                !alignmentAvailable -> "Download word synchronization to test"
                                model !in installedSpeech || !alignmentInstalled -> "Existing setup will be verified and updated before testing"
                                model == "kokoro-misaki" -> if (SpeechDistribution.IS_PLAY_BUILD) "Misaki English pronunciation · on-device Kokoro speech" else "Same Kokoro voice · Misaki English pronunciation · compare with Kokoro"
                                else -> "Installed · actual voice generation and alignment"
                            }, model in speechModels && available && alignmentAvailable, available && alignmentAvailable) { selected ->
                                speechModels = if (selected) (speechModels + model).distinct() else speechModels - model
                            }
                        }
                        if (!alignmentInstalled || installedSpeech.size < SpeechDistribution.benchmarkModels.count { it != "sc-kokoro" }) TextButton(onClick = { vm.screen.value = "voices" }) {
                            Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Manage voice models")
                        }
                        if (!SpeechDistribution.IS_PLAY_BUILD) {
                        BenchmarkChoice("SC Kokoro · external experiment", if (external.installed && alignmentAvailable && !alignmentInstalled)
                            "Existing word synchronization setup will be verified and updated before testing" else external.message,
                            "sc-kokoro" in speechModels && external.installed && alignmentAvailable, external.installed && alignmentAvailable) { selected ->
                            speechModels = if (selected) (speechModels + "sc-kokoro").distinct() else speechModels - "sc-kokoro"
                        }
                        Text("Tests the separately installed SC Kokoro app with its Heart voice. Synthetic test text is sent locally to that engine. ReadFlow cannot verify GPU use or measure the other app's RAM; results are labeled external.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { runCatching { uriHandler.openUri(ScKokoroEngine.SOURCE_URL) }
                            .onFailure { vm.message.value = "Could not open SC Kokoro setup" } }) { Text("SC Kokoro setup and device test") }
                        }
                        HorizontalDivider()
                        Text("TEXT RECOGNITION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        BenchmarkChoice("ML Kit · Original", "Built in · finance text and table sample", "mlkit" in ocrEngines, true) { selected ->
                            ocrEngines = if (selected) (ocrEngines + "mlkit").distinct() else ocrEngines - "mlkit"
                        }
                        BenchmarkChoice("TeleOCR", if (teleInstalled) "Installed · may take several minutes per page" else "Download TeleOCR to compare", "teleocr" in ocrEngines, teleInstalled) { selected ->
                            ocrEngines = if (selected) (ocrEngines + "teleocr").distinct() else ocrEngines - "teleocr"
                        }
                        BenchmarkChoice("PaddleOCR · mobile Latin", if (paddleInstalled) "Installed · independent detector and recognizer" else "Download PaddleOCR to compare",
                            "paddleocr" in ocrEngines, paddleInstalled) { selected ->
                            ocrEngines = if (selected) (ocrEngines + "paddleocr").distinct() else ocrEngines - "paddleocr"
                        }
                        TextButton(onClick = { vm.screen.value = "ocr" }) {
                            Icon(Icons.Default.DocumentScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("OCR setup · ${ocrQualityLabel(prefs.ocrQuality)}")
                        }
                    }
                }
                item {
                    SettingsCard("Test length", Icons.Default.Timer) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 3, 5).forEach { count ->
                                FilterChip(selected = repetitions == count, onClick = { repetitions = count },
                                    label = { Text("$count ${if (count == 1) "sample" else "samples"}") }, modifier = Modifier.weight(1f))
                            }
                        }
                        Text("Speech adds an initial sample. OCR repeats the same synthetic page to compare fresh and warm sessions; these are not different book pages.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SettingsSwitch("Pronunciation comparison samples", "Names, amounts, years and words such as record and read. Choose 5 samples to include every sentence. Timing checks do not score pronunciation.", pronunciationSamples) {
                            pronunciationSamples = it
                        }
                        SettingsSwitch("Compare CPU thread counts", "Test 2, 3 and 4 speech threads in separate warm blocks. More threads may increase heat.", compareThreads) { compareThreads = it }
                        Text("Extra sustained speech samples", style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0, 30, 60).forEach { count ->
                                FilterChip(selected = sustained == count, onClick = { sustained = count },
                                    label = { Text(if (count == 0) "Off" else "$count") }, modifier = Modifier.weight(1f))
                            }
                        }
                        Text("Sustained tests use your chosen ${prefs.speechThreads} speech threads and ${prefs.alignmentThreads} timing threads. They generate audio without playing it and can take several minutes.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Reading pauses during testing. Keep ReadFlow open. For a fair comparison, use similar battery and temperature conditions.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { cancellationRequested = false; configuring = false; vm.startBenchmark(BenchmarkOptions(
                            speechModels = eligibleSpeech, ocrEngines = eligibleOcr, voice = prefs.voice,
                            ocrQuality = prefs.ocrQuality, repetitions = repetitions,
                            speechThreads = prefs.speechThreads, alignmentThreads = prefs.alignmentThreads, allowSpinning = prefs.allowSpinning,
                            compareCpuThreads = compareThreads, sustainedSpeechSamples = sustained,
                            speechFixture = if (pronunciationSamples) "pronunciation" else "financial",
                        )) }, enabled = !active && (eligibleSpeech.isNotEmpty() || eligibleOcr.isNotEmpty()), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
                            Text(if (state.rows.isEmpty()) "Run phone test" else "Run a new test")
                        }
                    }
                }
            }
            if (state.status != BenchmarkStatus.IDLE) item {
                SettingsCard(if (stopping) "Stopping test" else when (state.status) {
                    BenchmarkStatus.RUNNING -> "Testing this phone"
                    BenchmarkStatus.COMPLETED -> "Test complete"
                    BenchmarkStatus.CANCELLED -> "Test cancelled"
                    BenchmarkStatus.FAILED -> "Test stopped"
                    else -> "Phone test"
                }, if (active) Icons.Default.Speed else Icons.Default.Assessment) {
                    Text(state.phase, style = MaterialTheme.typography.bodyMedium)
                    if (active) {
                        if (!stopping && state.total > 0) LinearProgressIndicator(progress = { (state.completed.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text("${state.completed} / ${state.total} steps · ${metricSeconds(state.elapsedMs)}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${(state.options.speechModels + state.options.ocrEngines).joinToString { modelDisplayName(it) }} · ${ocrQualityLabel(state.options.ocrQuality)} OCR",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.currentDevice?.processPssMiB?.let { MetricDetail("Current ReadFlow memory", "${metricNumber(it, 0)} MiB") }
                    state.cancellationReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (active) OutlinedButton(onClick = { cancellationRequested = true; vm.cancelBenchmark() }, enabled = !stopping,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Stop, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (stopping) "Stopping…" else "Cancel test")
                    }
                    else if (state.rows.isNotEmpty()) FilledTonalButton(onClick = vm::copyBenchmarkResults, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copy results")
                    }
                }
            }
            if (state.rows.isNotEmpty()) {
                item {
                    Text("Your measurements", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp))
                    Text("Speech preparation above ${metricNumber(prefs.speed.toDouble())}× realtime keeps up with your current ${metricNumber(prefs.speed.toDouble())}× listening speed on these samples.",
                        Modifier.padding(horizontal = 4.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(state.rows, key = { index, row -> "$index:${row.kind}:${row.model}:${row.sample}" }) { _, row ->
                    BenchmarkResultCard(row, if (active) null else { { chosen ->
                        vm.updateSpeechPerformance(prefs.copy(speechThreads = chosen.speechThreads ?: prefs.speechThreads,
                            alignmentThreads = chosen.alignmentThreads ?: prefs.alignmentThreads,
                            allowSpinning = chosen.allowSpinning ?: prefs.allowSpinning))
                        vm.message.value = "CPU settings applied to future reading"
                    } })
                }
                item {
                    SettingsCard("What these results mean", Icons.Default.Info) {
                        Text("Chars/min measures generation or extraction speed, not reading speed. Speech preparation includes the time needed for word timing; higher is faster.", style = MaterialTheme.typography.bodySmall)
                        Text("OCR character errors compare only the built-in fixture, preserving accents and punctuation. Lower is better. Scoring uses text before narration punctuation. Table cells and structure are scored separately when the engine provides them. Neither is a general book accuracy score.", style = MaterialTheme.typography.bodySmall)
                        Text("This test generates audio without playing it. It does not score pronunciation, speaker quality or Bluetooth latency.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.before != null || state.after != null) item {
                DeviceConditions(state.before, state.after, state.highestObservedPssMiB, state.memorySampleCount, state.schemaVersion >= 2)
            }
        }
    }
}

@Composable private fun BenchmarkChoice(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun BenchmarkResultCard(row: BenchmarkRow, applyCpu: ((BenchmarkRow) -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    Card(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(when {
                    row.status == BenchmarkRowStatus.FAILED -> Icons.Default.ErrorOutline
                    row.status == BenchmarkRowStatus.SKIPPED -> Icons.Default.RemoveCircleOutline
                    row.kind == "ocr" -> Icons.Default.DocumentScanner
                    else -> Icons.Default.GraphicEq
                }, null, Modifier.size(22.dp), tint = if (row.status == BenchmarkRowStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(modelDisplayName(row.model), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(row.sample + if (row.cold) when (row.kind) {
                        "ocr" -> if (row.model == "mlkit") " · first request" else " · fresh model load"
                        "speech" -> " · first run"
                        else -> ""
                    } else "", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(20.dp))
            }
            if (row.status == BenchmarkRowStatus.SUCCESS) {
                if (row.kind == "speech-load") MetricDetail("Model setup", metricSeconds(row.totalMs))
                else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) { MetricDetail(if (row.kind == "ocr") "Extraction" else "Speech generation",
                        row.charactersPerMinute?.let { "${metricNumber(it, 0)} chars/min" } ?: "Not measured") }
                    Column(Modifier.weight(1f)) {
                        if (row.kind == "ocr") MetricDetail("Character errors", row.characterErrorRate?.let { "${metricNumber(it * 100)}%" } ?: "Not measured")
                        else MetricDetail("Preparation", row.preparationSpeed?.let { "${metricNumber(it)}× realtime" } ?: "Not measured")
                    }
                }
            } else Text(if (row.status == BenchmarkRowStatus.FAILED) "Test failed" else "Test skipped", style = MaterialTheme.typography.bodyMedium,
                color = if (row.status == BenchmarkRowStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.alignmentAccepted == false) Text("Audio generated; word timing did not pass the quality check.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (expanded) {
                HorizontalDivider()
                MetricDetail("Total elapsed", metricSeconds(row.totalMs))
                row.verifyMs?.let { MetricDetail("Model verification", metricSeconds(it)) }
                row.loadMs?.let { MetricDetail("Model loading", metricSeconds(it)) }
                row.synthesisMs?.let { MetricDetail("Speech generation time", metricSeconds(it)) }
                row.phonemizerMs?.let { MetricDetail("Pronunciation preparation", metricSeconds(it)) }
                row.speechInferenceMs?.let { MetricDetail("Speech model inference", metricSeconds(it)) }
                row.alignmentMs?.let { MetricDetail("Word timing time", metricSeconds(it)) }
                row.resampleMs?.let { MetricDetail("Audio resampling", metricSeconds(it)) }
                row.alignmentInferenceMs?.let { MetricDetail("Timing model inference", metricSeconds(it)) }
                row.alignmentDecodeMs?.let { MetricDetail("Timing decode", metricSeconds(it)) }
                row.backend?.let { MetricDetail("Execution backend", it) }
                row.speechThreads?.let { MetricDetail("Speech CPU threads", it.toString()) }
                row.alignmentThreads?.let { MetricDetail("Timing CPU threads", it.toString()) }
                row.allowSpinning?.let { MetricDetail("Worker spinning", if (it) "On" else "Off") }
                row.ocrLayoutMs?.let { MetricDetail("Layout detection", metricSeconds(it)) }
                row.ocrRecognitionMs?.let { MetricDetail("Region recognition", metricSeconds(it)) }
                row.observedPeakPssMiB?.let { MetricDetail("Highest sampled memory during step", "${metricNumber(it, 0)} MiB · ${row.memorySamples} samples") }
                row.audioSeconds?.let { MetricDetail("Generated audio", "${metricNumber(it)} s") }
                row.characters?.let { MetricDetail("Characters", it.toString()) }
                row.tableReadingOrderCorrect?.let { MetricDetail("Fixture table token sequence", if (it) "Matched" else "Not matched") }
                row.tableStructureCorrect?.let { MetricDetail("Table structure", if (it) "Matched" else "Not matched") }
                row.tableCellAccuracy?.let { MetricDetail("Correct table cells", "${metricNumber(it * 100)}%") }
                row.recognizedText?.let { Text("Recognized fixture text", style = MaterialTheme.typography.labelMedium)
                    Text(it, style = MaterialTheme.typography.bodySmall) }
                row.inputText?.let { MetricDetail("Speech fixture text", it) }
                row.normalizedSpeechText?.let { MetricDetail("Text sent to speech engine", it) }
                row.alignmentAccepted?.let { MetricDetail("Word timing quality check", if (it) "Accepted" else "Not accepted") }
                row.alignedWords?.let { MetricDetail("Aligned words", it.toString()) }
                row.voice?.let { MetricDetail("Voice", it) }
                row.modelVersion?.let { MetricDetail("Model version", it) }
                if (applyCpu != null && row.status == BenchmarkRowStatus.SUCCESS && row.kind == "speech" && row.backend == "cpu" && row.speechThreads != null) {
                    TextButton(onClick = { applyCpu(row) }) { Text("Use these CPU settings") }
                }
                row.deviceAfter?.let { device ->
                    device.processPssMiB?.let { MetricDetail("App memory after this step", "${metricNumber(it, 0)} MiB") }
                    device.thermalStatus?.let { MetricDetail("Thermal status after this step", thermalLabel(it)) }
                    device.batteryTemperatureC?.let { MetricDetail("Battery temperature after this step", temperature(it)) }
                }
            }
            if (expanded || row.status == BenchmarkRowStatus.FAILED) row.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (row.status == BenchmarkRowStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun DeviceConditions(before: DeviceSnapshot?, after: DeviceSnapshot?, highestSampledPss: Double?, samples: Int, sampledDuringWork: Boolean) {
    SettingsCard("Device conditions", Icons.Default.PhoneAndroid) {
        (after ?: before)?.let { MetricDetail("Phone", "${it.device} · Android API ${it.androidApi}") }
        Text("Snapshots before and after the test, not peak values.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        MetricDetail("Thermal status · before → after", "${thermalLabel(before?.thermalStatus)} → ${thermalLabel(after?.thermalStatus)}")
        (after ?: before)?.charging?.let { MetricDetail("Charging", if (it) "Yes" else "No") }
        (after ?: before)?.powerSave?.let { MetricDetail("Battery saver", if (it) "On" else "Off") }
        MetricDetail("Battery temperature · before → after", "${temperature(before?.batteryTemperatureC)} → ${temperature(after?.batteryTemperatureC)}")
        MetricDetail("App memory · before → after", "${memory(before?.processPssMiB)} → ${memory(after?.processPssMiB)}")
        highestSampledPss?.let { MetricDetail("Highest sampled app memory", "${metricNumber(it, 0)} MiB · $samples snapshots") }
        Text(if (sampledDuringWork) "Memory is sampled every second during processing and at step boundaries. Brief peaks can still be missed. External speech-engine memory is not included."
            else "This older report sampled memory only between steps and may miss TeleOCR's working memory. Run a new test for corrected OCR scoring and memory samples.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("App memory is the process’s proportional memory share. It includes the app and all loaded components.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun temperature(value: Double?) = value?.let { "${metricNumber(it)} °C" } ?: "—"
private fun memory(value: Double?) = value?.let { "${metricNumber(it, 0)} MiB" } ?: "—"
private fun thermalLabel(value: Int?) = when (value) {
    0 -> "Normal"
    1 -> "Light"
    2 -> "Moderate"
    3 -> "Severe"
    4 -> "Critical"
    5 -> "Emergency"
    6 -> "Shutdown"
    else -> "—"
}
