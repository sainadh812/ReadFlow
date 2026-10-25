package app.readflow.ingest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import app.readflow.core.ExtractedPage
import app.readflow.core.TextElement
import app.readflow.models.ModelStore
import java.io.File
import java.nio.FloatBuffer
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.*

/** Actual PP-OCRv5 mobile DB detection + Latin CTC recognition, using pinned upstream ONNX models.
 * ML Kit reference words supply geometry only when exact text agrees; they never replace output. */
class PaddleOcrEngine(private val models: ModelStore) {
    private val mutex = Mutex()
    private val idleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idleRelease: Job? = null
    private var detection: OrtSession? = null
    private var recognition: OrtSession? = null
    private var vocabulary: List<String> = emptyList()
    private var threads = 0
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private val mutableProgress = MutableStateFlow<String?>(null)
    val progress = mutableProgress.asStateFlow()
    private val mutableLastRun = MutableStateFlow<String?>(null)
    val lastRun = mutableLastRun.asStateFlow()
    @Volatile var lastMetrics: OcrRunMetrics? = null
        private set

    suspend fun recognize(bitmap: Bitmap, quality: OcrQuality, referenceWords: List<TextElement> = emptyList()): ExtractedPage =
        withContext(Dispatchers.Default) { mutex.withLock {
            idleRelease?.cancel(); idleRelease = null
            val started = SystemClock.elapsedRealtime()
            lastMetrics = null
            val cold = detection == null || recognition == null || threads != quality.paddleThreads
            try {
                currentCoroutineContext().ensureActive()
                mutableProgress.value = "PaddleOCR · Loading mobile models"
                load(quality)
                val loadMs = SystemClock.elapsedRealtime() - started
                val layoutStarted = SystemClock.elapsedRealtime()
                mutableProgress.value = "PaddleOCR · Finding text regions"
                val (w, h) = PaddleOcrPostprocess.detectorSize(bitmap.width, bitmap.height, quality.paddleDetectionSide)
                val input = pixels(bitmap, w, h, detection = true)
                val map = infer(checkNotNull(detection), input, longArrayOf(1, 3, h.toLong(), w.toLong()))
                check(map.shape.size == 4 && map.shape[0] == 1L && map.shape[1] == 1L) { "Unsupported PaddleOCR detector output" }
                val job = currentCoroutineContext()
                val regions = PaddleOcrPostprocess.detect(map.values, map.shape[3].toInt(), map.shape[2].toInt(), bitmap.width, bitmap.height) { job.ensureActive() }
                val layoutMs = SystemClock.elapsedRealtime() - layoutStarted
                val recognitionStarted = SystemClock.elapsedRealtime()
                val recognized = mutableListOf<RecognizedLine>()
                var uncertain = 0; var empty = 0
                for ((index, region) in regions.withIndex()) {
                    job.ensureActive()
                    mutableProgress.value = "PaddleOCR · Reading region ${index + 1} of ${regions.size}"
                    val cropped = crop(bitmap, region)
                    try {
                        // Refuse to squash unusually long lines: their text would silently disappear.
                        val scaledWidth = ceil(48.0 * cropped.width / cropped.height).toInt().coerceAtLeast(16)
                        check(scaledWidth <= 3200) { "PaddleOCR text line is too wide; crop or rotate this page" }
                        val modelWidth = max(320, scaledWidth)
                        val data = pixels(cropped, scaledWidth, 48, detection = false, paddedWidth = modelWidth)
                        val output = infer(checkNotNull(recognition), data, longArrayOf(1, 3, 48, modelWidth.toLong()))
                        check(output.shape.size == 3 && output.shape[0] == 1L) { "Unsupported PaddleOCR recognizer output" }
                        val decoded = PaddleOcrPostprocess.decode(output.values, output.shape[1].toInt(), output.shape[2].toInt(), vocabulary)
                        if (decoded.text.isNotBlank()) {
                            if (decoded.confidence < .8f) uncertain++
                            recognized += RecognizedLine(region, decoded.text)
                        } else empty++
                    } finally { cropped.recycle() }
                }
                val ordered = orderLines(recognized, bitmap.width)
                val usedReferences = mutableSetOf<TextElement>()
                val elements = ordered.flatMapIndexed { index, line -> PaddleOcrPostprocess.words(line.text, line.quad, referenceWords, index, usedReferences) }
                val missing = elements.count { it.boxes.isEmpty() }
                val warnings = buildList {
                    if (missing > 0) add("Exact word highlights are unavailable for $missing of ${elements.size} PaddleOCR words; the recognized words remain available in text view.")
                    if (uncertain > 0) add("$uncertain PaddleOCR text regions have low recognition confidence; compare with the original page.")
                    if (empty > 0) add("PaddleOCR detected $empty regions without readable text; inspect the original page.")
                    if (elements.isEmpty()) add("PaddleOCR found no readable text. Try a different scan rotation or OCR model.")
                    add("PaddleOCR recognizes text and orders lines; it does not reconstruct merged table cells.")
                }
                lastMetrics = OcrRunMetrics(cold, loadMs, layoutMs, SystemClock.elapsedRealtime() - recognitionStarted,
                    "ONNX Runtime CPU · ${quality.paddleThreads} threads")
                val elapsed = (SystemClock.elapsedRealtime() - started) / 1000.0
                mutableLastRun.value = String.format(Locale.US, "PaddleOCR · %.2f seconds · %d regions", elapsed, regions.size)
                ExtractedPage(elements, bitmap.width.toFloat(), bitmap.height.toFloat(), "PaddleOCR · PP-OCRv5 Latin · ${quality.id}",
                    warnings, preserveOrder = true, recognitionText = ordered.joinToString("\n") { it.text })
            } finally {
                mutableProgress.value = null
                idleRelease = idleScope.launch { delay(45_000); mutex.withLock { release() } }
            }
        } }

    /** Drains an active call before releasing native sessions; safe on memory pressure and model deletion. */
    suspend fun close() = withContext(Dispatchers.IO) { mutex.withLock {
        idleRelease?.cancel(); idleRelease = null; release()
    } }
    suspend fun deleteModels() = withContext(Dispatchers.IO) { mutex.withLock {
        idleRelease?.cancel(); idleRelease = null; release(); models.delete("paddleocr")
    } }

    private suspend fun load(quality: OcrQuality) {
        models.verifyInstalled("paddleocr")
        if (detection != null && recognition != null && threads == quality.paddleThreads) return
        release()
        val root = models.directory("paddleocr")
        vocabulary = PaddleOcrPostprocess.dictionary(File(root, "rec.yml").readText())
        try {
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(quality.paddleThreads)
                options.setInterOpNumThreads(1)
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                options.addConfigEntry("session.inter_op.allow_spinning", "0")
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                detection = environment.createSession(File(root, "det.onnx").absolutePath, options)
                currentCoroutineContext().ensureActive()
                recognition = environment.createSession(File(root, "rec.onnx").absolutePath, options)
                threads = quality.paddleThreads
            }
        } catch (error: Throwable) { release(); throw error }
    }

    private fun release() {
        try { recognition?.close() } finally {
            recognition = null
            try { detection?.close() } finally { detection = null; vocabulary = emptyList(); threads = 0 }
        }
    }
    private data class Output(val values: FloatArray, val shape: LongArray)
    private suspend fun infer(session: OrtSession, values: FloatArray, shape: LongArray): Output = coroutineScope {
        currentCoroutineContext().ensureActive()
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape).use { input ->
            OrtSession.RunOptions().use { options ->
                val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { options.setTerminate(true) }
                }
                try {
                    session.run(mapOf(session.inputNames.single() to input), options).use { result ->
                        currentCoroutineContext().ensureActive()
                        val tensor = result[0] as OnnxTensor
                        val info = tensor.info as TensorInfo
                        check(info.numElements in 1..8_000_000) { "PaddleOCR output exceeded memory limit" }
                        val data = FloatArray(info.numElements.toInt())
                        tensor.floatBuffer.get(data)
                        Output(data, info.shape)
                    }
                } finally { withContext(NonCancellable) { cancellation.cancelAndJoin() } }
            }
        }
    }

    /** Detector uses BGR/ImageNet normalization; recognition uses RGB/[-1,1], per upstream Android deployment. */
    private fun pixels(source: Bitmap, width: Int, height: Int, detection: Boolean, paddedWidth: Int = width): FloatArray {
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        try {
            val argb = IntArray(width * height); scaled.getPixels(argb, 0, width, 0, 0, width, height)
            val plane = paddedWidth * height
            val output = FloatArray(3 * plane)
            val means = floatArrayOf(.485f, .456f, .406f); val std = floatArrayOf(.229f, .224f, .225f)
            for (y in 0 until height) for (x in 0 until width) {
                val pixel = argb[y * width + x]; val destination = y * paddedWidth + x
                for (channel in 0..2) {
                    val shift = if (detection) channel * 8 else (2 - channel) * 8
                    val value = (pixel ushr shift and 255).toFloat()
                    output[channel * plane + destination] = if (detection) (value / 255f - means[channel]) / std[channel] else value / 127.5f - 1f
                }
            }
            return output
        } finally { if (scaled !== source) scaled.recycle() }
    }

    private fun crop(source: Bitmap, quad: PaddleQuad): Bitmap {
        val p = quad.points
        val width = max(hypot(p[1].x - p[0].x, p[1].y - p[0].y), hypot(p[2].x - p[3].x, p[2].y - p[3].y)).roundToInt().coerceAtLeast(2)
        val height = max(hypot(p[3].x - p[0].x, p[3].y - p[0].y), hypot(p[2].x - p[1].x, p[2].y - p[1].y)).roundToInt().coerceAtLeast(2)
        check(width.toLong() * height <= 4_000_000) { "PaddleOCR region exceeded the bitmap limit" }
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            target.eraseColor(Color.WHITE)
            val transform = Matrix()
            check(transform.setPolyToPoly(p.flatMap { listOf(it.x, it.y) }.toFloatArray(), 0,
                floatArrayOf(0f, 0f, width - 1f, 0f, width - 1f, height - 1f, 0f, height - 1f), 0, 4)) { "Invalid PaddleOCR crop geometry" }
            Canvas(target).drawBitmap(source, transform, Paint(Paint.FILTER_BITMAP_FLAG))
            if (height.toFloat() / width >= 1.5f) {
                val rotated = Bitmap.createBitmap(target, 0, 0, width, height, Matrix().apply { postRotate(-90f) }, true)
                if (rotated !== target) target.recycle()
                return rotated
            }
            return target
        } catch (error: Throwable) { target.recycle(); throw error }
    }

    private data class RecognizedLine(val quad: PaddleQuad, val text: String)
    private fun orderLines(lines: List<RecognizedLine>, pageWidth: Int): List<RecognizedLine> {
        if (lines.isEmpty()) return lines
        val middle = pageWidth / 2f
        val columns = lines.count { it.quad.bounds.right < middle - pageWidth * .02f } >= 3 &&
            lines.count { it.quad.bounds.left > middle + pageWidth * .02f } >= 3 &&
            lines.count { it.quad.bounds.left < middle && it.quad.bounds.right > middle } <= 2
        val groups = if (columns) lines.groupBy { if (it.quad.bounds.left < middle) 0 else 1 }.toSortedMap().values else listOf(lines)
        return groups.flatMap { group ->
            val rows = mutableListOf<MutableList<RecognizedLine>>()
            for (line in group.sortedBy { it.quad.bounds.top }) {
                val box = line.quad.bounds
                val row = rows.lastOrNull()?.takeIf { previous ->
                    val anchor = previous.first().quad.bounds
                    abs((box.top + box.bottom) / 2 - (anchor.top + anchor.bottom) / 2) < min(box.height, anchor.height) * .5f
                }
                if (row == null) rows += mutableListOf(line) else row += line
            }
            rows.flatMap { it.sortedBy { line -> line.quad.bounds.left } }
        }
    }
}
