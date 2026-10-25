package app.readflow.benchmark

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
import app.readflow.ingest.LocalExtraction
import app.readflow.ingest.OcrEnhancement
import app.readflow.ingest.OcrQuality
import app.readflow.ingest.TeleOcrEngine
import app.readflow.models.ModelStore
import app.readflow.speech.OnnxForcedAligner
import app.readflow.speech.SherpaEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs the production normalization, speech, alignment and OCR code against synthetic fixtures.
 * The owner must suspend reading and document extraction for the lifetime of run(). Native work
 * drains before resource cleanup, including cancellation; no two benchmark models run together.
 */
class BenchmarkRunner(
    private val context: Context,
    private val models: ModelStore,
    private val ocrModels: ModelStore,
    private val teleOcr: TeleOcrEngine,
) {
    private val lock = Mutex()
    private val store = BenchmarkStore(File(context.noBackupFilesDir, "benchmark"))

    suspend fun run(options: BenchmarkOptions, onUpdate: (BenchmarkState) -> Unit): BenchmarkState =
        withContext(Dispatchers.Default) { lock.withLock {
            val selected = options.validated()
            val started = SystemClock.elapsedRealtime()
            val before = snapshot()
            var state = BenchmarkState(appVersion = BuildConfig.VERSION_NAME, status = BenchmarkStatus.RUNNING,
                options = selected, startedAtMs = System.currentTimeMillis(), before = before,
                highestObservedPssMiB = before.processPssMiB, memorySampleCount = if (before.processPssMiB == null) 0 else 1,
                total = selected.speechModels.size * (selected.repetitions + 2) + selected.ocrEngines.size * selected.repetitions)
            val temporary = File(context.cacheDir, "benchmark-work").apply { mkdirs() }
            val job = currentCoroutineContext().job
            var speechEngine: SherpaEngine? = null
            var speechAligner: OnnxForcedAligner? = null
            var extraction: LocalExtraction? = null

            suspend fun cleanupAttempt(close: suspend () -> Unit): Throwable? = try {
                close(); null
            } catch (failure: Exception) { failure }
            catch (failure: LinkageError) { failure }

            suspend fun releaseSpeech(): Throwable? {
                // Transfer ownership before JNI teardown: a throwing native free must never be retried.
                val engine = speechEngine; speechEngine = null
                val aligner = speechAligner; speechAligner = null
                val engineFailure = cleanupAttempt { engine?.unload() }
                val alignmentFailure = cleanupAttempt { aligner?.close() }
                return engineFailure ?: alignmentFailure
            }

            suspend fun releaseExtraction(): Throwable? {
                val local = extraction; extraction = null
                return cleanupAttempt { local?.close() }
            }

            fun cleanupWarning(failure: Throwable) {
                state = state.copy(error = listOfNotNull(state.error,
                    "A model could not close cleanly: ${safeError(failure)}").joinToString(" ").take(2400))
            }

            suspend fun publish() {
                state = state.copy(elapsedMs = SystemClock.elapsedRealtime() - started)
                onUpdate(state)
                try { withContext(Dispatchers.IO) { store.save(state) } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    // A full disk must not erase completed rows or mask a cancellation while draining native work.
                    val warning = "A report save failed. Completed measurements remain on this screen; copy the report to keep them."
                    state = state.copy(error = if (state.error?.contains(warning) == true) state.error
                        else listOfNotNull(state.error, warning).joinToString(" ").take(2400))
                    onUpdate(state)
                }
            }
            suspend fun phase(label: String) { state = state.copy(phase = label); publish() }
            suspend fun record(row: BenchmarkRow, count: Int = 1) {
                val device = snapshot()
                state = state.copy(rows = state.rows + row.copy(deviceAfter = device),
                    completed = (state.completed + count).coerceAtMost(state.total),
                    highestObservedPssMiB = listOfNotNull(state.highestObservedPssMiB, device.processPssMiB).maxOrNull(),
                    memorySampleCount = state.memorySampleCount + if (device.processPssMiB == null) 0 else 1)
                publish()
            }

            try {
                publish()
                for (model in selected.speechModels) {
                    currentCoroutineContext().ensureActive()
                    val pack = models.packs.first { it.id == model }
                    if (!models.installed(model) || !models.installed("alignment")) {
                        record(BenchmarkRow("speech", model, "Not installed", BenchmarkRowStatus.SKIPPED,
                            note = "Install this speech model and Word alignment in Settings first."), selected.repetitions + 2)
                        continue
                    }
                    val voice = pack.voices.firstOrNull { it.id == selected.voice }?.id ?: pack.voices.first().id
                    val engine = SherpaEngine(models::directory) { id -> models.packs.first { it.id == id }.voices }
                    val aligner = OnnxForcedAligner { File(models.directory("alignment"), "model.onnx") }
                    speechEngine = engine; speechAligner = aligner
                    try {
                        phase("$model · verifying installed models")
                        val verifyStart = SystemClock.elapsedRealtime()
                        models.verifyInstalled(model); models.verifyInstalled("alignment")
                        val verifyMs = SystemClock.elapsedRealtime() - verifyStart
                        phase("$model · loading speech model")
                        val loadStart = SystemClock.elapsedRealtime()
                        engine.load(model, voice)
                        val loadMs = SystemClock.elapsedRealtime() - loadStart
                        record(BenchmarkRow("speech-load", model, "Fresh speech session", cold = true,
                            totalMs = verifyMs + loadMs, verifyMs = verifyMs, loadMs = loadMs, voice = voice,
                            modelVersion = pack.version,
                            note = "Verification may use this process's verified-file cache. OS file cache is not cleared. Alignment loads during the first speech sample."))
                        for (sample in 0..selected.repetitions) {
                            currentCoroutineContext().ensureActive()
                            val cold = sample == 0
                            val label = if (cold) "First speech sample" else "Warm speech sample $sample"
                            phase("$model · $label")
                            val text = BenchmarkFixtures.SPEECH[(sample - 1).coerceAtLeast(0) % BenchmarkFixtures.SPEECH.size]
                            val row = try {
                                speechSample(engine, aligner, text, model, voice, label, cold,
                                    pack.version, File(temporary, "speech.wav")) { job.isActive }
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) {
                                currentCoroutineContext().ensureActive()
                                BenchmarkRow("speech", model, label, BenchmarkRowStatus.FAILED, cold = cold,
                                    voice = voice, modelVersion = pack.version, note = safeError(failure))
                            }
                            record(row)
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) {
                        currentCoroutineContext().ensureActive()
                        val done = state.rows.count { it.model == model }
                        record(BenchmarkRow("speech-load", model, "Speech setup failed", BenchmarkRowStatus.FAILED,
                            note = safeError(failure)), (selected.repetitions + 2 - done).coerceAtLeast(1))
                    } catch (failure: LinkageError) {
                        currentCoroutineContext().ensureActive()
                        val done = state.rows.count { it.model == model }
                        record(BenchmarkRow("speech-load", model, "Native speech runtime unavailable", BenchmarkRowStatus.FAILED,
                            note = failure.javaClass.simpleName), (selected.repetitions + 2 - done).coerceAtLeast(1))
                    } finally {
                        withContext(NonCancellable) {
                            releaseSpeech()?.let { failure ->
                                cleanupWarning(failure)
                                if (job.isActive) throw IllegalStateException("Speech model cleanup failed", failure)
                            }
                        }
                    }
                }

                if (selected.ocrEngines.isNotEmpty()) {
                    phase("Preparing the synthetic financial page")
                    val fixture = File(temporary, "financial-page.png")
                    renderFixture(fixture)
                    for (ocr in selected.ocrEngines) {
                        currentCoroutineContext().ensureActive()
                        if (ocr == "teleocr" && !ocrModels.installed("teleocr")) {
                            record(BenchmarkRow("ocr", ocr, "Not installed", BenchmarkRowStatus.SKIPPED,
                                note = "Install TeleOCR in Settings first. No substitute engine was tested."), selected.repetitions)
                            continue
                        }
                        val local = LocalExtraction()
                        extraction = local
                        try {
                            repeat(selected.repetitions) { index ->
                                currentCoroutineContext().ensureActive()
                                val cold = ocr == "teleocr" || index == 0
                                val label = "Financial page ${index + 1}"
                                phase("$ocr · $label of ${selected.repetitions}")
                                val sampleStarted = SystemClock.elapsedRealtime()
                                val row = try {
                                    val enhance: OcrEnhancement? = if (ocr == "teleocr") { bitmap, quality, words ->
                                        val result = teleOcr.recognize(bitmap, quality, words)
                                        check(result.method.startsWith("TeleOCR")) {
                                            "TeleOCR failed; ML Kit fallback is excluded from this test. ${result.warnings.joinToString(" ")}"
                                        }
                                        result
                                    } else null
                                    val extracted = local.extract(fixture.path, 0, quality = OcrQuality.fromId(selected.ocrQuality), enhancement = enhance)
                                    val page = DocumentPipeline().process("benchmark", 0, extracted)
                                    val ms = SystemClock.elapsedRealtime() - sampleStarted
                                    val characters = BenchmarkMetrics.characters(page.reading)
                                    BenchmarkRow("ocr", ocr, label, cold = cold, totalMs = ms, characters = characters,
                                        charactersPerMinute = BenchmarkMetrics.charactersPerMinute(characters, ms),
                                        characterErrorRate = BenchmarkMetrics.characterErrorRate(BenchmarkFixtures.OCR_REFERENCE, page.reading),
                                        tableReadingOrderCorrect = BenchmarkMetrics.tableReadingOrderCorrect(page.reading),
                                        modelVersion = if (ocr == "teleocr") ocrModels.packs.first { it.id == ocr }.version else "ML Kit Latin 16.0.1",
                                        note = buildString {
                                            append("Synthetic paragraph and table only; lower character error rate is better. Includes image decode, OCR, retries and reading order. ")
                                            if (ocr == "teleocr") append("Includes ML Kit geometry and loading TeleOCR anew for each page. ")
                                            append(extracted.warnings.joinToString(" "))
                                        }.take(1600))
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (failure: Exception) {
                                    currentCoroutineContext().ensureActive()
                                    BenchmarkRow("ocr", ocr, label, BenchmarkRowStatus.FAILED, cold = cold,
                                        totalMs = SystemClock.elapsedRealtime() - sampleStarted, note = safeError(failure))
                                } catch (failure: LinkageError) {
                                    BenchmarkRow("ocr", ocr, label, BenchmarkRowStatus.FAILED, cold = cold,
                                        totalMs = SystemClock.elapsedRealtime() - sampleStarted, note = failure.javaClass.simpleName)
                                }
                                record(row)
                            }
                        } finally {
                            withContext(NonCancellable) {
                                releaseExtraction()?.let { failure ->
                                    cleanupWarning(failure)
                                    if (job.isActive) throw IllegalStateException("OCR cleanup failed", failure)
                                }
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val failures = state.rows.count { it.status == BenchmarkRowStatus.FAILED }
                state = state.copy(status = BenchmarkStatus.COMPLETED,
                    phase = if (failures == 0) "Test complete" else "Test complete · $failures failed measurements")
            } catch (cancel: CancellationException) {
                state = state.copy(status = BenchmarkStatus.CANCELLED, phase = "Test cancelled",
                    error = listOfNotNull(state.error, "Completed measurements were kept.").joinToString(" ").take(2400))
                throw cancel
            } catch (failure: Exception) {
                state = state.copy(status = BenchmarkStatus.FAILED, phase = "Test could not finish",
                    error = listOfNotNull(state.error, safeError(failure)).joinToString(" ").take(2400))
            } catch (failure: LinkageError) {
                state = state.copy(status = BenchmarkStatus.FAILED, phase = "Native runtime unavailable",
                    error = listOfNotNull(state.error, safeError(failure)).joinToString(" ").take(2400))
            } finally {
                withContext(NonCancellable) {
                    // Cleanup continues even when cancellation arrived inside synchronous native inference.
                    val speechCleanupFailure = releaseSpeech()
                    val ocrCleanupFailure = releaseExtraction()
                    listOfNotNull(speechCleanupFailure, ocrCleanupFailure).forEach { failure ->
                        cleanupWarning(failure)
                        if (state.status != BenchmarkStatus.CANCELLED) {
                            state = state.copy(status = BenchmarkStatus.FAILED, phase = "Test cleanup failed")
                        }
                    }
                    temporary.deleteRecursively()
                    val after = snapshot()
                    state = state.copy(after = after,
                        highestObservedPssMiB = listOfNotNull(state.highestObservedPssMiB, after.processPssMiB).maxOrNull(),
                        memorySampleCount = state.memorySampleCount + if (after.processPssMiB == null) 0 else 1)
                    publish()
                }
            }
            state
        } }

    private suspend fun speechSample(engine: SherpaEngine, aligner: OnnxForcedAligner, text: String,
        model: String, voice: String, label: String, cold: Boolean, version: String,
        wave: File, isCurrent: () -> Boolean): BenchmarkRow {
        val started = SystemClock.elapsedRealtime()
        val page = DocumentPipeline().process("benchmark", 0,
            ExtractedPage(listOf(TextElement(text, emptyList())), 0f, 0f, "Synthetic benchmark"))
        val prepared = SpeechPlanner().prepare(page.words).toList()
        var synthesisMs = 0L; var alignmentMs = 0L; var audioSeconds = 0.0; var alignedWords = 0
        var accepted = true
        for (part in prepared) {
            currentCoroutineContext().ensureActive()
            var stageStarted = SystemClock.elapsedRealtime()
            val audio = engine.synthesize(part.speech.text, isCurrent)
            synthesisMs += SystemClock.elapsedRealtime() - stageStarted
            val quantized = withContext(Dispatchers.IO) { writeQuantizedWave(wave, audio) }
            currentCoroutineContext().ensureActive()
            stageStarted = SystemClock.elapsedRealtime()
            val alignment = alignForPlayback(aligner, quantized, part.speech)
            alignmentMs += SystemClock.elapsedRealtime() - stageStarted
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
            note = "CPU · 2 speech threads · 2 alignment threads. Characters/min uses normalized speech and synthesis time. " +
                "Preparation includes normalization, synthesis, PCM16 WAV writing and word alignment; excludes playback and database cache commit. " +
                if (accepted) "Alignment passed its confidence checks; this does not score pronunciation." else "Audio generated, but word alignment was rejected; no word timings were invented.")
    }

    private fun snapshot(): DeviceSnapshot {
        val thermal = runCatching { context.getSystemService(PowerManager::class.java).currentThermalStatus }.getOrNull()
        val temperature = runCatching {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10.0)
        }.getOrNull()
        val pss = runCatching { Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss / 1024.0 }.getOrNull()
        return DeviceSnapshot("${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.SDK_INT, thermal, temperature, pss)
    }

    private fun safeError(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(1600)
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
