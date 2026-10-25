package app.readflow.ingest

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import app.readflow.core.*
import app.readflow.models.ModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.*

/** Local, opt-in TeleOCR. Models live only for one page and are released before returning. */
class TeleOcrEngine(private val context: Context, private val models: ModelStore) {
    private val lock = Mutex()
    private val mutableProgress = MutableStateFlow<String?>(null)
    val progress = mutableProgress.asStateFlow()
    private val mutableLastRun = MutableStateFlow<String?>(null)
    val lastRun = mutableLastRun.asStateFlow()

    suspend fun deleteModels() = lock.withLock { models.delete("teleocr") }

    suspend fun recognize(bitmap: Bitmap, quality: OcrQuality, reference: List<TextElement>): ExtractedPage =
        withContext(Dispatchers.IO) { lock.withLock {
            val started = System.nanoTime()
            try {
                check(models.installed("teleocr")) { "Download TeleOCR in Settings first" }
                val memory = ActivityManager.MemoryInfo()
                context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
                val requiredMemory = if (quality == OcrQuality.HIGH) 3L * 1024 * 1024 * 1024 else 2560L * 1024 * 1024
                check(!memory.lowMemory && memory.availMem >= requiredMemory) {
                    "TeleOCR needs more free memory; close other apps and retry"
                }
                mutableProgress.value = "Checking TeleOCR model"
                models.verifyInstalled("teleocr")
                val directory = models.directory("teleocr")
                mutableProgress.value = "Loading TeleOCR"
                // Layout uses the upstream 1036-square input (1369 visual tokens).
                // Reducing that pass below its intended size can lose whole columns.
                val imageTokens = if (quality == OcrQuality.HIGH) 2048 else 1369
                val cropPixels = when (quality) { OcrQuality.FAST -> 512 * 784; OcrQuality.BALANCED -> 1024 * 784; OcrQuality.HIGH -> 2048 * 784 }
                val threads = when (quality) { OcrQuality.FAST -> 2; OcrQuality.BALANCED -> 3; OcrQuality.HIGH -> 4 }
                val result = withTimeout(10 * 60_000L) {
                    val session = TeleOcrNative.create(threads)
                    try {
                        coroutineScope {
                            // Native calls are synchronous; cancellation must reach them from another thread.
                            val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                                try { awaitCancellation() } finally { session.cancel() }
                            }
                            try {
                                currentCoroutineContext().ensureActive()
                                session.load(File(directory, "NaviDC-OCR-Q5_K_M.gguf").path,
                                    File(directory, "NaviDC-OCR-mmproj-q8_0.gguf").path, imageTokens)
                                currentCoroutineContext().ensureActive()
                                mutableProgress.value = "TeleOCR · finding text and tables"
                                val layoutImage = Bitmap.createScaledBitmap(bitmap, 1036, 1036, true)
                                val rawLayout = try { infer(session, layoutImage, TeleOcrOutput.LAYOUT_PROMPT, 4096) }
                                    finally { if (layoutImage !== bitmap) layoutImage.recycle() }
                                val layout = TeleOcrOutput.parseLayout(rawLayout, bitmap.width.toFloat(), bitmap.height.toFloat())
                                check(layout.regions.isNotEmpty()) { "TeleOCR could not locate text on this page" }
                                check(layout.regions.size <= 64) { "This page is too complex for local TeleOCR" }
                                val regions = layout.regions.mapIndexed { index, region ->
                                    currentCoroutineContext().ensureActive()
                                    mutableProgress.value = "TeleOCR · section ${index + 1} of ${layout.regions.size}"
                                    if (!TeleOcrOutput.needsRecognition(region)) region
                                    else {
                                        val crop = crop(bitmap, region.box, region.angle)
                                        try {
                                            val prompt = TeleOcrOutput.promptFor(region)
                                            region.copy(content = infer(session, crop, prompt, 4096, cropPixels))
                                        } finally { crop.recycle() }
                                    }
                                }
                                val adapted = TeleOcrOutput.adapt(regions, reference)
                                check(adapted.elements.isNotEmpty()) { "TeleOCR returned no readable text" }
                                // A failed/partial layout must not silently discard a large portion of a book page.
                                val located = reference.count { word -> word.boxes.any { box -> regions.any { region ->
                                    region.content.isNotBlank() && region.box.intersection(box) >= box.width * box.height * .5f
                                } } }
                                check(reference.size < 10 || located >= reference.size * .8f) {
                                    "TeleOCR left too much detected text outside its reading regions"
                                }
                                ExtractedPage(adapted.elements, bitmap.width.toFloat(), bitmap.height.toFloat(),
                                    "TeleOCR · ${quality.id}", layout.warnings + adapted.warnings, preserveOrder = true,
                                    tables = adapted.tables.map { table -> RecognizedTable(regions[table.regionIndex].box,
                                        table.rowCount, table.columnCount, table.cells.map { cell ->
                                            RecognizedCell(cell.text, cell.row, cell.column, cell.rowSpan, cell.columnSpan, cell.header)
                                        }) })
                            } finally { withContext(NonCancellable) { cancellation.cancelAndJoin() } }
                        }
                    } finally { session.close() }
                }
                mutableLastRun.value = "TeleOCR · ${elapsed(started)} seconds"
                result.copy(method = "${result.method} · ${elapsed(started)}s")
            } catch (timeout: TimeoutCancellationException) {
                // A page timeout is recoverable; navigation/user cancellation still propagates below.
                currentCoroutineContext().ensureActive()
                fallback(bitmap, reference, "TeleOCR took too long", started)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                fallback(bitmap, reference, error.message ?: "TeleOCR could not recognize this page", started)
            } catch (_: LinkageError) {
                fallback(bitmap, reference, "The local TeleOCR runtime could not load on this device", started)
            } finally { mutableProgress.value = null }
        } }

    private fun fallback(bitmap: Bitmap, words: List<TextElement>, reason: String, started: Long): ExtractedPage {
        mutableLastRun.value = "Used ML Kit fallback · ${elapsed(started)} seconds"
        return ExtractedPage(words, bitmap.width.toFloat(), bitmap.height.toFloat(), "Latin OCR · TeleOCR fallback",
            listOf("$reason. ML Kit text was kept; choose Retry text recognition to try again."))
    }

    private fun elapsed(started: Long) = ((System.nanoTime() - started) / 1_000_000_000L).coerceAtLeast(1)

    private suspend fun infer(session: TeleOcrNative.Session, bitmap: Bitmap, prompt: String, tokens: Int, maxPixels: Int = 2_000_000): String {
        currentCoroutineContext().ensureActive()
        // Bound temporary Java allocations independently of the source PDF render size.
        val scale = minOf(1.0, sqrt(maxPixels.toDouble() / (bitmap.width.toDouble() * bitmap.height)))
        val input = if (scale < 1) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true) else bitmap
        try {
            val row = IntArray(input.width)
            val rgb = ByteArray(input.width * input.height * 3)
            var offset = 0
            for (y in 0 until input.height) {
                currentCoroutineContext().ensureActive()
                input.getPixels(row, 0, input.width, 0, y, input.width, 1)
                for (pixel in row) {
                    rgb[offset++] = (pixel shr 16).toByte(); rgb[offset++] = (pixel shr 8).toByte(); rgb[offset++] = pixel.toByte()
                }
            }
            val result = session.recognize(rgb, input.width, input.height, prompt, tokens)
            currentCoroutineContext().ensureActive()
            check(result.isNotBlank()) { "TeleOCR returned empty text" }
            return result
        } finally { if (input !== bitmap) input.recycle() }
    }

    private fun crop(source: Bitmap, box: Box, angle: Int): Bitmap {
        val left = floor(box.left).toInt().coerceIn(0, source.width - 1)
        val top = floor(box.top).toInt().coerceIn(0, source.height - 1)
        val right = ceil(box.right).toInt().coerceIn(left + 1, source.width)
        val bottom = ceil(box.bottom).toInt().coerceIn(top + 1, source.height)
        // Always own the cropped bitmap; createBitmap may otherwise return the source.
        val region = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        val owned = if (region === source) region.copy(Bitmap.Config.ARGB_8888, false) else region
        if (angle == 0) return owned
        return try { Bitmap.createBitmap(owned, 0, 0, owned.width, owned.height,
            Matrix().apply { postRotate(-angle.toFloat()) }, true) }
        finally { owned.recycle() }
    }
}
