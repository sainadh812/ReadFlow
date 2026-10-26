package app.readflow.benchmark

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import app.readflow.BuildConfig
import app.readflow.core.*
import app.readflow.ingest.*
import app.readflow.models.ModelStore
import app.readflow.speech.*
import app.readflow.speech.misaki.MisakiFrontend
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Real production pipelines on original synthetic fixtures; no reading or OCR may run concurrently. */
class BenchmarkRunner(
    private val context: Context,
    private val models: ModelStore,
    private val ocrModels: ModelStore,
    private val teleOcr: TeleOcrEngine,
    private val paddleOcr: PaddleOcrEngine,
) {
    private val lock = Mutex()
    private val store = BenchmarkStore(File(context.noBackupFilesDir, "benchmark"))

    suspend fun run(options: BenchmarkOptions, onUpdate: (BenchmarkState) -> Unit): BenchmarkState =
        withContext(Dispatchers.Default) { lock.withLock { coroutineScope {
            val selected = options.validatedForExecution()
            val started = SystemClock.elapsedRealtime()
            val before = snapshot()
            val samples = BenchmarkMemorySamples().apply { add(before.processPssMiB) }
            val publication = Mutex()
            var state = BenchmarkState(appVersion = BuildConfig.VERSION_NAME, status = BenchmarkStatus.RUNNING,
                options = selected, startedAtMs = System.currentTimeMillis(), before = before, currentDevice = before,
                speechFixtureVersion = BenchmarkFixtures.SPEECH_VERSION,
                total = benchmarkMeasurementCount(selected))
            val basePhase = AtomicReference("Preparing phone benchmark")
            val detail = AtomicReference<() -> String?>({ null })
            val temporary = File(context.cacheDir, "benchmark-work").apply { check(isDirectory || mkdirs()) }
            val job = currentCoroutineContext().job
            var speechEngine: TtsEngine? = null
            var speechAligner: OnnxForcedAligner? = null
            var speechFrontend: MisakiFrontend? = null
            var extraction: LocalExtraction? = null

            suspend fun update(persist: Boolean = false, transform: (BenchmarkState) -> BenchmarkState = { it }) {
                val captured = publication.withLock {
                    val memory = samples.overall()
                    state = transform(state).copy(elapsedMs = SystemClock.elapsedRealtime() - started,
                        highestObservedPssMiB = memory.peakMiB, memorySampleCount = memory.count)
                    onUpdate(state)
                    state
                }
                if (persist) try { withContext(Dispatchers.IO) { store.save(captured) } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    publication.withLock {
                        val warning = "A report save failed; copy the results to keep them."
                        state = state.copy(error = if (state.error?.contains(warning) == true) state.error
                            else listOfNotNull(state.error, warning).joinToString(" ").take(2400))
                        onUpdate(state)
                    }
                }
            }
            suspend fun phase(label: String, progress: () -> String? = { null }) {
                basePhase.set(label); detail.set(progress)
                update(persist = true) { it.copy(phase = label) }
            }
            fun beginMeasurement() { samples.beginRow(); samples.add(snapshot().processPssMiB) }
            suspend fun record(row: BenchmarkRow, count: Int = 1) {
                val device = snapshot()
                samples.add(device.processPssMiB)
                val memory = samples.finishRow()
                update(persist = true) { it.copy(rows = it.rows + row.copy(deviceAfter = device,
                    observedPeakPssMiB = memory.peakMiB, memorySamples = memory.count), currentDevice = device,
                    completed = (it.completed + count).coerceAtMost(it.total)) }
            }
            suspend fun cleanupAttempt(close: suspend () -> Unit): Throwable? = try { close(); null }
                catch (failure: Exception) { failure } catch (failure: LinkageError) { failure }
            suspend fun releaseSpeech(): Throwable? {
                val engine = speechEngine; speechEngine = null
                val aligner = speechAligner; speechAligner = null
                val frontend = speechFrontend; speechFrontend = null
                val failure = cleanupAttempt { engine?.unload() }
                val frontendFailure = cleanupAttempt { frontend?.close() }
                return cleanupAttempt { aligner?.close() } ?: frontendFailure ?: failure
            }
            suspend fun releaseExtraction(): Throwable? {
                val local = extraction; extraction = null
                return cleanupAttempt { local?.close() }
            }
            suspend fun releaseOcr(): Throwable? {
                val local = releaseExtraction()
                val tele = cleanupAttempt { teleOcr.close() }
                val paddle = cleanupAttempt { paddleOcr.close() }
                return local ?: tele ?: paddle
            }
            suspend fun cleanupWarning(failure: Throwable) = update {
                it.copy(error = listOfNotNull(it.error, "Model cleanup failed: ${safeError(failure)}").joinToString(" ").take(2400))
            }
            // Sampling is concurrent with blocking native inference. This ticker never writes/fsyncs
            // reports; state is persisted only at phase/row transitions and completion.
            val samplerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val ticker = samplerScope.launch {
                while (isActive) {
                    delay(1000)
                    val device = snapshot()
                    samples.add(device.processPssMiB)
                    update { current -> current.copy(currentDevice = device, phase = if (current.running) {
                        listOfNotNull(basePhase.get(), detail.get().invoke()?.takeIf(String::isNotBlank)).joinToString(" · ")
                    } else current.phase) }
                }
            }

            try {
                update(persist = true)
                // A previous reader OCR session must not contaminate speech memory/CPU comparisons.
                releaseOcr()?.let { throw IllegalStateException("Could not release prior OCR model", it) }
                for (model in selected.speechModels) {
                    val external = model == ScKokoroEngine.MODEL_ID
                    val misaki = model == MisakiKokoroEngine.MODEL_ID
                    val packId = if (misaki) "kokoro" else model
                    val passes = speechPasses(selected, model)
                    val required = passes.sumOf { it.measurements }
                    currentCoroutineContext().ensureActive()
                    val pack = models.packs.firstOrNull { it.id == packId }
                    // A prior app version's local files are candidates for checked migration.
                    // verifyInstalled below must complete before any inference session opens.
                    val available = if (external) ScKokoroEngine.availability(context).installed else models.hasLocalInstallation(packId)
                    if (!available || !models.hasLocalInstallation("alignment")) {
                        record(BenchmarkRow("speech", model, "Not ready", BenchmarkRowStatus.SKIPPED,
                            backend = if (external) "external-unverified" else "cpu",
                            note = if (external) "Install SC Kokoro separately, finish its model download/device test, and install Read Assit's Word alignment. No CPU substitute was tested."
                                else "Install this speech model and Word alignment first."), required)
                        continue
                    }
                    val voice = if (external) selected.voice.takeIf { it in setOf("af_heart", "af_bella", "af_nicole") } ?: ScKokoroEngine.DEFAULT_VOICE
                        else pack!!.voices.firstOrNull { it.id == selected.voice }?.id ?: pack.voices.first().id
                    val version = if (external) "SC Kokoro ${ScKokoroEngine.availability(context).version ?: "unknown version"}"
                        else pack!!.version + if (misaki) " + ${MisakiFrontend.VERSION} + ${MisakiKokoroEngine.ADAPTER_VERSION}" else ""
                    for ((passIndex, pass) in passes.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val backend = if (external) "external-unverified" else "cpu"
                        val title = if (pass.sustained) "$model · sustained preparation" else "$model · block ${passIndex + 1}/${passes.size}"
                        val configLabel = pass.threads?.let { "$it speech threads" } ?: "external engine"
                        var done = 0
                        try {
                            beginMeasurement()
                            phase("$title · $configLabel · verifying")
                            val verifyStart = SystemClock.elapsedRealtime()
                            if (!external) models.verifyInstalled(packId)
                            models.verifyInstalled("alignment")
                            val verifyMs = SystemClock.elapsedRealtime() - verifyStart
                            phase("$title · $configLabel · loading")
                            val loadStart = SystemClock.elapsedRealtime()
                            val engine: TtsEngine = when {
                                external -> ScKokoroEngine(context).also { speechEngine = it }
                                misaki -> {
                                    val frontend = withContext(Dispatchers.IO) {
                                        MisakiFrontend(MisakiFrontend.ortLibraryPath(context), threads = 1).also { speechFrontend = it }
                                    }
                                    MisakiKokoroEngine(models::directory,
                                        { id -> models.packs.first { it.id == id }.voices }, { frontend.phonemize(it) }).also {
                                        speechEngine = it
                                        it.configure(SpeechRuntimeConfig(pass.threads!!, selected.allowSpinning))
                                    }
                                }
                                else -> SpeechDistribution.newLegacyEngine(models::directory) { id -> models.packs.first { it.id == id }.voices }.also {
                                    speechEngine = it
                                    SpeechDistribution.configureLegacy(it, SpeechRuntimeConfig(pass.threads!!, selected.allowSpinning))
                                }
                            }
                            val aligner = OnnxForcedAligner { File(models.directory("alignment"), "model.onnx") }.also { speechAligner = it }
                            aligner.configure(SpeechRuntimeConfig(selected.alignmentThreads, selected.allowSpinning))
                            engine.load(packId, voice)
                            val loadMs = SystemClock.elapsedRealtime() - loadStart
                            record(BenchmarkRow("speech-load", model, "$configLabel · fresh session", cold = !external,
                                totalMs = verifyMs + loadMs, verifyMs = verifyMs, loadMs = loadMs, voice = voice,
                                modelVersion = version, backend = backend, speechThreads = pass.threads,
                                alignmentThreads = selected.alignmentThreads, allowSpinning = selected.allowSpinning,
                                note = if (external) "Connects to a separate app. Its internal model may already be warm. Android TTS does not expose GPU/CPU placement; Read Assit PSS excludes the external process."
                                    else (if (misaki) "Misaki POS and neural pronunciation models are included in model loading; one pronunciation thread. " else "") +
                                        "One resident speech model. Blocks run sequentially; later blocks may encounter more heat. Verification/OS caches are not cleared; alignment loads in the first sample."))
                            done++
                            for (sample in 0..pass.warmSamples) {
                                currentCoroutineContext().ensureActive()
                                val cold = sample == 0 && !external
                                val label = "$configLabel · " + if (sample == 0) "First sample" else if (pass.sustained) "Sustained sample $sample/${pass.warmSamples}" else "Warm sample $sample"
                                beginMeasurement()
                                phase("$title · $label")
                                val fixtures = BenchmarkFixtures.speechSamples(selected.speechFixture)
                                val text = fixtures[(sample - 1).coerceAtLeast(0) % fixtures.size]
                                val sampleStart = SystemClock.elapsedRealtime()
                                val row = try {
                                    speechSample(engine, aligner, text, model, voice, label, cold, version,
                                        File(temporary, "speech.wav"), selected, pass.threads) { job.isActive }
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (failure: Exception) {
                                    currentCoroutineContext().ensureActive()
                                    BenchmarkRow("speech", model, label, BenchmarkRowStatus.FAILED, cold = cold,
                                        totalMs = SystemClock.elapsedRealtime() - sampleStart, voice = voice, modelVersion = version,
                                        backend = backend, speechThreads = pass.threads, alignmentThreads = selected.alignmentThreads,
                                        allowSpinning = selected.allowSpinning, inputText = text, note = safeError(failure))
                                }
                                record(row); done++
                            }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) {
                            currentCoroutineContext().ensureActive()
                            record(BenchmarkRow("speech-load", model, "$configLabel · setup failed", BenchmarkRowStatus.FAILED,
                                backend = backend, speechThreads = pass.threads, note = safeError(failure)), (pass.measurements - done).coerceAtLeast(1))
                        } catch (failure: LinkageError) {
                            record(BenchmarkRow("speech-load", model, "$configLabel · native runtime unavailable", BenchmarkRowStatus.FAILED,
                                backend = backend, note = safeError(failure)), (pass.measurements - done).coerceAtLeast(1))
                        } finally {
                            withContext(NonCancellable) { releaseSpeech()?.let { failure ->
                                cleanupWarning(failure)
                                if (job.isActive) throw IllegalStateException("Speech cleanup failed", failure)
                            } }
                        }
                    }
                }

                if (selected.ocrEngines.isNotEmpty()) {
                    phase("Preparing the synthetic financial page")
                    val fixture = File(temporary, "financial-page.png")
                    renderFixture(fixture)
                    for (ocr in selected.ocrEngines) {
                        currentCoroutineContext().ensureActive()
                        if (ocr != "mlkit" && !ocrModels.installed(ocr)) {
                            record(BenchmarkRow("ocr", ocr, "Not installed", BenchmarkRowStatus.SKIPPED,
                                note = "Install $ocr first. No substitute engine was tested."), selected.repetitions)
                            continue
                        }
                        releaseOcr()?.let { throw IllegalStateException("Could not release OCR model", it) }
                        val local = LocalExtraction()
                        extraction = local
                        try {
                            repeat(selected.repetitions) { index ->
                                currentCoroutineContext().ensureActive()
                                val label = "Financial page ${index + 1}"
                                beginMeasurement()
                                phase("$ocr · $label of ${selected.repetitions}") {
                                    when (ocr) { "teleocr" -> teleOcr.progress.value; "paddleocr" -> paddleOcr.progress.value; else -> null }
                                }
                                val sampleStart = SystemClock.elapsedRealtime()
                                val row = try {
                                    val enhancement: OcrEnhancement? = when (ocr) {
                                        "teleocr" -> { bitmap, quality, words -> teleOcr.recognize(bitmap, quality, words).also {
                                            check(it.method.startsWith("TeleOCR")) { "TeleOCR failed; ML Kit fallback is excluded. ${it.warnings.joinToString(" ")}" }
                                        } }
                                        "paddleocr" -> { bitmap, quality, words -> paddleOcr.recognize(bitmap, quality, words).also {
                                            check(it.method.startsWith("PaddleOCR")) { "PaddleOCR failed; substitute output is excluded." }
                                        } }
                                        else -> null
                                    }
                                    val extracted = local.extract(fixture.path, 0, quality = OcrQuality.fromId(selected.ocrQuality), enhancement = enhancement)
                                    val page = DocumentPipeline().process("benchmark", 0, extracted)
                                    val raw = extracted.recognitionText ?: page.original
                                    val ms = SystemClock.elapsedRealtime() - sampleStart
                                    val metrics = when (ocr) { "teleocr" -> teleOcr.lastMetrics; "paddleocr" -> paddleOcr.lastMetrics; else -> null }
                                    val characters = BenchmarkMetrics.characters(raw)
                                    BenchmarkRow("ocr", ocr, label, cold = metrics?.cold ?: (index == 0), totalMs = ms,
                                        loadMs = metrics?.loadMs, ocrLayoutMs = metrics?.layoutMs, ocrRecognitionMs = metrics?.recognitionMs,
                                        characters = characters, charactersPerMinute = BenchmarkMetrics.charactersPerMinute(characters, ms),
                                        characterErrorRate = BenchmarkMetrics.characterErrorRate(BenchmarkFixtures.OCR_REFERENCE, raw),
                                        tableReadingOrderCorrect = BenchmarkMetrics.tableReadingOrderCorrect(raw),
                                        tableStructureCorrect = if (ocr != "teleocr" && extracted.tables.isEmpty()) null else BenchmarkMetrics.tableStructureCorrect(extracted.tables),
                                        tableCellAccuracy = if (ocr != "teleocr" && extracted.tables.isEmpty()) null else BenchmarkMetrics.tableCellAccuracy(extracted.tables),
                                        recognizedText = raw.take(8192), referenceText = BenchmarkFixtures.OCR_REFERENCE,
                                        backend = metrics?.backend ?: "ML Kit · hardware backend not exposed",
                                        modelVersion = if (ocr == "mlkit") "ML Kit Latin 16.0.1" else ocrModels.packs.first { it.id == ocr }.version,
                                        note = buildString {
                                            append("Raw OCR is scored before speech cleanup; synthetic paragraph/table only. Includes decode, geometry, retries and reading order. ")
                                            append("Sessions are released before this engine's first page and reused between repetitions. ")
                                            append("The text sequence check includes exact cell spelling; structure and cell-position scores are reported separately. ")
                                            append(extracted.warnings.joinToString(" "))
                                        }.take(1600))
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (failure: Exception) {
                                    currentCoroutineContext().ensureActive()
                                    BenchmarkRow("ocr", ocr, label, BenchmarkRowStatus.FAILED, cold = index == 0,
                                        totalMs = SystemClock.elapsedRealtime() - sampleStart, note = safeError(failure))
                                } catch (failure: LinkageError) {
                                    BenchmarkRow("ocr", ocr, label, BenchmarkRowStatus.FAILED, cold = index == 0,
                                        totalMs = SystemClock.elapsedRealtime() - sampleStart, note = safeError(failure))
                                }
                                record(row)
                            }
                        } finally {
                            withContext(NonCancellable) { releaseOcr()?.let { failure ->
                                cleanupWarning(failure)
                                if (job.isActive) throw IllegalStateException("OCR cleanup failed", failure)
                            } }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                update { val failures = it.rows.count { row -> row.status == BenchmarkRowStatus.FAILED }
                    it.copy(status = BenchmarkStatus.COMPLETED, phase = if (failures == 0) "Test complete" else "Test complete · $failures failed measurements") }
            } catch (cancel: CancellationException) {
                withContext(NonCancellable) { update { it.copy(status = BenchmarkStatus.CANCELLED, phase = "Test cancelled",
                    error = listOfNotNull(it.error, "Completed measurements were kept.").joinToString(" ").take(2400)) } }
                throw cancel
            } catch (failure: Exception) {
                update { it.copy(status = BenchmarkStatus.FAILED, phase = "Test could not finish", error = safeError(failure)) }
            } catch (failure: LinkageError) {
                update { it.copy(status = BenchmarkStatus.FAILED, phase = "Native runtime unavailable", error = safeError(failure)) }
            } finally {
                withContext(NonCancellable) {
                    // Keep sampling throughout cleanup; a cancelled large model may take time to drain.
                    val speechFailure = releaseSpeech()
                    val ocrFailure = releaseOcr()
                    listOfNotNull(speechFailure, ocrFailure).forEach { failure ->
                        cleanupWarning(failure)
                        update { if (it.status == BenchmarkStatus.CANCELLED) it else it.copy(status = BenchmarkStatus.FAILED, phase = "Test cleanup failed") }
                    }
                    ticker.cancelAndJoin()
                    samplerScope.cancel()
                    temporary.deleteRecursively()
                    val after = snapshot(); samples.add(after.processPssMiB)
                    update(persist = true) { it.copy(after = after, currentDevice = after) }
                }
            }
            state
        } } }

    private suspend fun speechSample(engine: TtsEngine, aligner: OnnxForcedAligner, text: String,
        model: String, voice: String, label: String, cold: Boolean, version: String,
        wave: File, options: BenchmarkOptions, threads: Int?, isCurrent: () -> Boolean): BenchmarkRow {
        val started = SystemClock.elapsedRealtime()
        val page = DocumentPipeline().process("benchmark", 0,
            ExtractedPage(listOf(TextElement(text, emptyList())), 0f, 0f, "Synthetic benchmark"))
        val prepared = SpeechPlanner().prepare(page.words).toList()
        var synthesisMs = 0L; var alignmentMs = 0L; var audioSeconds = 0.0; var alignedWords = 0
        var resampleMs = 0L; var inferenceMs = 0L; var decodeMs = 0L; var loadMs = 0L; var normalizationMs = 0L; var copyMs = 0L
        var phonemizerMs = 0L; var speechInferenceMs = 0L
        var accepted = true
        for (part in prepared) {
            currentCoroutineContext().ensureActive()
            var stage = SystemClock.elapsedRealtime()
            val audio = engine.synthesize(part.speech.text, isCurrent)
            synthesisMs += SystemClock.elapsedRealtime() - stage
            if (engine is MisakiKokoroEngine) {
                phonemizerMs += engine.lastTimings.phonemizerMs
                speechInferenceMs += engine.lastTimings.inferenceMs
            }
            val quantized = withContext(Dispatchers.IO) { writeQuantizedWave(wave, audio) }
            currentCoroutineContext().ensureActive()
            stage = SystemClock.elapsedRealtime()
            val alignment = alignForPlayback(aligner, quantized, part.speech)
            alignmentMs += SystemClock.elapsedRealtime() - stage
            val timing = aligner.lastTimings
            resampleMs += timing.resampleMs; inferenceMs += timing.inferenceMs; decodeMs += timing.ctcMs
            loadMs += timing.sessionLoadMs; normalizationMs += timing.normalizationMs; copyMs += timing.outputCopyMs
            audioSeconds += audio.samples.size.toDouble() / audio.sampleRate
            alignedWords += alignment.timings.size
            accepted = accepted && alignment.rejected == null && alignment.timings.isNotEmpty()
        }
        val total = SystemClock.elapsedRealtime() - started
        val characters = prepared.sumOf { BenchmarkMetrics.characters(it.speech.text) }
        return BenchmarkRow("speech", model, label, cold = cold, totalMs = total,
            synthesisMs = synthesisMs, alignmentMs = alignmentMs, characters = characters,
            audioSeconds = audioSeconds, charactersPerMinute = BenchmarkMetrics.charactersPerMinute(characters, synthesisMs),
            preparationSpeed = BenchmarkMetrics.preparationSpeed(audioSeconds, total), alignedWords = alignedWords,
            alignmentAccepted = accepted, voice = voice, modelVersion = version,
            backend = if (engine is ScKokoroEngine) engine.lastExecution?.backend ?: "external-unverified" else "cpu",
            speechThreads = threads, alignmentThreads = options.alignmentThreads, allowSpinning = options.allowSpinning,
            resampleMs = resampleMs, alignmentInferenceMs = inferenceMs, alignmentDecodeMs = decodeMs,
            alignmentLoadMs = loadMs, alignmentNormalizationMs = normalizationMs, alignmentOutputCopyMs = copyMs,
            phonemizerMs = phonemizerMs.takeIf { engine is MisakiKokoroEngine },
            speechInferenceMs = speechInferenceMs.takeIf { engine is MisakiKokoroEngine },
            inputText = text, normalizedSpeechText = prepared.joinToString(" ") { it.speech.text },
            note = buildString {
                if (engine is ScKokoroEngine) append("SC Kokoro external engine; GPU/CPU placement unverified. Engine-process memory is excluded. Synthesis includes Android IPC and external WAV handling. ")
                else append("CPU · $threads speech threads · spinning ${if (options.allowSpinning) "on" else "off"}. ")
                if (engine is MisakiKokoroEngine) append("Experimental Misaki English with neural fallback, one pronunciation thread, direct ONNX Kokoro. Uses the same voice weights; pronunciation can differ. ")
                append("${options.alignmentThreads} alignment threads. Characters/min uses synthesis time. Preparation includes normalization, synthesis, PCM16 writing and alignment, not playback/database commit. ")
                append(if (accepted) "Alignment passed; this does not score pronunciation or playback gaps." else "Alignment rejected; no timings were invented.")
            })
    }

    private fun snapshot(): DeviceSnapshot {
        val power = context.getSystemService(PowerManager::class.java)
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return DeviceSnapshot("${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.SDK_INT,
            thermalStatus = runCatching { power.currentThermalStatus }.getOrNull(),
            batteryTemperatureC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10.0),
            processPssMiB = runCatching { Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss / 1024.0 }.getOrNull(),
            charging = status?.takeIf { it >= 0 }?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL },
            powerSave = runCatching { power.isPowerSaveMode }.getOrNull(),
            availableMemoryMiB = runCatching { ActivityManager.MemoryInfo().also {
                context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
            }.availMem / 1048576.0 }.getOrNull())
    }
    private fun safeError(error: Throwable) = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(1600)
}

/** Matches AudioCache's PCM16 conversion so the aligner sees the same samples as production. */
private fun writeQuantizedWave(file: File, audio: PcmAudio): PcmAudio {
    require(audio.sampleRate > 0 && audio.samples.isNotEmpty())
    require(audio.samples.size.toDouble() / audio.sampleRate <= 40) { "Generated sample exceeds the alignment limit" }
    val bytes = ByteBuffer.allocate(44 + audio.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    bytes.put("RIFF".toByteArray()).putInt(36 + audio.samples.size * 2).put("WAVEfmt ".toByteArray()).putInt(16)
    bytes.putShort(1).putShort(1).putInt(audio.sampleRate).putInt(audio.sampleRate * 2).putShort(2).putShort(16)
    bytes.put("data".toByteArray()).putInt(audio.samples.size * 2)
    val samples = FloatArray(audio.samples.size) { index ->
        val value = (audio.samples[index].coerceIn(-1f, 1f) * 32767).toInt().toShort()
        bytes.putShort(value); value.toFloat() / 32768
    }
    file.outputStream().use { it.write(bytes.array()); it.fd.sync() }
    return PcmAudio(samples, audio.sampleRate)
}

private fun renderFixture(file: File) {
    val bitmap = Bitmap.createBitmap(1600, 1080, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 44f; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        BenchmarkFixtures.PARAGRAPH_LINES.forEachIndexed { index, line -> canvas.drawText(line, 72f, 112f + index * 78f, paint) }
        val columns = floatArrayOf(72f, 550f, 1050f, 1528f)
        val top = 480f; val height = 112f
        paint.strokeWidth = 2f
        for (row in 0..4) canvas.drawLine(columns.first(), top + row * height, columns.last(), top + row * height, paint)
        columns.forEach { canvas.drawLine(it, top, it, top + 4 * height, paint) }
        BenchmarkFixtures.TABLE_ROWS.forEachIndexed { row, cells ->
            cells.forEachIndexed { column, cell -> canvas.drawText(cell, columns[column] + 24f, top + row * height + 74f, paint) }
        }
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally { bitmap.recycle() }
}
