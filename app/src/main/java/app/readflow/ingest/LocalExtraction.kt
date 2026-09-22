package app.readflow.ingest

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.readflow.core.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlin.math.*

class LocalExtraction : PageExtractor {
    private val resourceLock = Mutex()
    // Rendering owns a separate renderer and can proceed while the single OCR task is running.
    private val renderLock = Mutex()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    suspend fun pageCount(path: String): Int = withContext(Dispatchers.IO) {
        resourceLock.withLock { openPdf(path).use { it.pageCount } }
    }
    private fun openPdf(path: String): PdfRenderer {
        val fd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        try { return PdfRenderer(fd) } catch (error: Exception) { fd.close(); throw error }
    }
    override suspend fun extract(path: String, page: Int, rotation: Int): ExtractedPage = withContext(Dispatchers.IO) {
        resourceLock.withLock {
            if (!path.endsWith(".pdf")) return@withLock extractImage(path, rotation)
            openPdf(path).use { renderer -> renderer.openPage(page).use { pdf ->
                val native = pdf.textContents
                val nativeText = native.joinToString("\n") { it.text }
                val matches = Regex("\\S+").findAll(nativeText).toList()
                val usable = nativeText.count { it.isLetter() } >= 24 && matches.size >= 6 &&
                    nativeText.count { it == '\uFFFD' || it.isISOControl() && !it.isWhitespace() } < nativeText.length / 20 + 1
                val hasImages = pdf.imageContents.isNotEmpty()
                if (usable && !hasImages && rotation == 0) {
                    val expected = matches.groupingBy { it.value }.eachCount()
                    val bounds = expected.keys.associateWith { token -> pdf.searchText(token).sortedBy { it.textStartIndex } }
                    if (expected.all { (word, count) -> bounds[word]?.size == count && bounds[word]!!.all { it.bounds.isNotEmpty() } }) {
                        val seen = mutableMapOf<String, Int>()
                        val elements = mutableListOf<TextElement>()
                        native.forEachIndexed { block, content -> Regex("\\S+").findAll(content.text).forEach { token ->
                            val occurrence = seen.getOrDefault(token.value, 0); seen[token.value] = occurrence + 1
                            elements += TextElement(token.value, bounds.getValue(token.value)[occurrence].bounds.map { Box(it.left, it.top, it.right, it.bottom) }, block, block)
                        } }
                        return@withLock ExtractedPage(elements, pdf.width.toFloat(), pdf.height.toFloat(), "Embedded PDF text")
                    }
                }
                val scale = minOf(300f / 72f, sqrt(OcrRefinement.MAX_SOURCE_PIXELS.toFloat() / (pdf.width.toFloat() * pdf.height)))
                val bitmap = Bitmap.createBitmap(maxOf(1, (pdf.width * scale).roundToInt()), maxOf(1, (pdf.height * scale).roundToInt()), Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    pdf.render(bitmap, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val result = recognize(bitmap, rotation, Transform(1 / scale, 0f, 0f, 1 / scale), pdf.width.toFloat(), pdf.height.toFloat())
                    result.copy(method = if (hasImages && usable) "Mixed page: ${result.method}" else result.method,
                        warnings = result.warnings + if (usable) listOf("OCR supplies word geometry on this page; check recognition against the original.") else emptyList())
                } finally { bitmap.recycle() }
            } }
        }
    }
    private suspend fun extractImage(path: String, rotation: Int): ExtractedPage {
        var sourceWidth = 0
        var sourceHeight = 0
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
            sourceWidth = info.size.width
            sourceHeight = info.size.height
            val scale = OcrRefinement.fitScale(sourceWidth, sourceHeight)
            decoder.setTargetSize((sourceWidth * scale).toInt().coerceAtLeast(1), (sourceHeight * scale).toInt().coerceAtLeast(1))
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val toPage = Transform(sourceWidth.toFloat() / bitmap.width, 0f, 0f, sourceHeight.toFloat() / bitmap.height)
        return try { recognize(bitmap, rotation, toPage, sourceWidth.toFloat(), sourceHeight.toFloat()) } finally { bitmap.recycle() }
    }

    private fun Transform.androidMatrix() = Matrix().apply {
        setValues(floatArrayOf(a, c, tx, b, d, ty, 0f, 0f, 1f))
    }

    private fun rotateBitmap(source: Bitmap, plan: OcrCanvas): Bitmap {
        val output = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        try {
            output.eraseColor(Color.WHITE)
            Canvas(output).drawBitmap(source, plan.sourceToCanvas.androidMatrix(), Paint(Paint.FILTER_BITMAP_FLAG))
            return output
        } catch (error: Throwable) { output.recycle(); throw error }
    }

    private suspend fun runOcr(bitmap: Bitmap): List<OcrLine> {
        currentCoroutineContext().ensureActive()
        // ML Kit's Task cannot be interrupted safely. Hold the bitmap/lock until it completes.
        val result = withContext(NonCancellable) { recognizer.process(InputImage.fromBitmap(bitmap, 0)).await() }
        currentCoroutineContext().ensureActive()
        return result.toLines()
    }

    private fun Text.toLines(): List<OcrLine> = textBlocks.flatMapIndexed { blockIndex, block ->
        block.lines.mapIndexedNotNull { lineIndex, line ->
            val words = line.elements.mapNotNull { element ->
                val box = element.boundingBox ?: return@mapNotNull null
                if (element.text.isBlank() || box.width() <= 0 || box.height() <= 0) return@mapNotNull null
                val corners = element.cornerPoints?.map { it.x.toFloat() to it.y.toFloat() }
                    ?.takeIf { it.size == 4 } ?: listOf(box.left.toFloat() to box.top.toFloat(),
                    box.right.toFloat() to box.top.toFloat(), box.right.toFloat() to box.bottom.toFloat(),
                    box.left.toFloat() to box.bottom.toFloat())
                OcrWord(element.text, OcrRefinement.bounds(corners), OcrRefinement.confidence(element.confidence), corners)
            }
            words.takeIf { it.isNotEmpty() }?.let { OcrLine(blockIndex, lineIndex, it, line.angle) }
        }
    }

    private suspend fun recognize(bitmap: Bitmap, rotation: Int, bitmapToPage: Transform, width: Float, height: Float): ExtractedPage {
        var working = bitmap
        var workingToBitmap = Transform()
        val warnings = mutableListOf<String>()
        try {
            if ((rotation % 360 + 360) % 360 != 0) {
                val plan = OcrRefinement.rotatedCanvas(bitmap.width, bitmap.height, rotation.toFloat())
                working = rotateBitmap(bitmap, plan)
                workingToBitmap = plan.sourceToCanvas.inverse()
            }
            var lines = runOcr(working)
            var deskewed = false
            val angle = OcrRefinement.deskewAngle(lines)
            if (angle != null) {
                val plan = OcrRefinement.rotatedCanvas(working.width, working.height, angle)
                val corrected = rotateBitmap(working, plan)
                var adopted = false
                try {
                    val candidate = runOcr(corrected)
                    val toPrevious = plan.sourceToCanvas.inverse()
                    if (OcrRefinement.keepDeskewed(lines, candidate.map { it.mapped(toPrevious) })) {
                        if (working !== bitmap) working.recycle()
                        working = corrected
                        workingToBitmap = OcrRefinement.compose(workingToBitmap, toPrevious)
                        lines = candidate
                        deskewed = true
                        adopted = true
                    }
                } catch (_: MlKitException) {
                    warnings += "Automatic straightening could not be checked; the original OCR was kept."
                } finally { if (!adopted) corrected.recycle() }
            }
            val refined = lines.toMutableList()
            var improved = 0
            for (index in OcrRefinement.retryIndices(lines)) {
                currentCoroutineContext().ensureActive()
                val line = lines[index]
                val crop = OcrRefinement.cropBounds(line, working.width, working.height)
                if (crop.width <= 0 || crop.height <= 0) continue
                val scale = OcrRefinement.cropScale(line, crop)
                val cropWidth = (crop.width * scale).toInt().coerceAtLeast(32)
                val cropHeight = (crop.height * scale).toInt().coerceAtLeast(32)
                val toCrop = Transform(scale, 0f, 0f, scale, -crop.left * scale, -crop.top * scale)
                // A short paragraph may not provide enough agreeing lines for page
                // deskew. Correct the focused crop using its own small line angle.
                val cropAngle = if (line.angle.isFinite() && abs(line.angle) in .8f..12f) -line.angle else 0f
                val cropPlan = OcrRefinement.rotatedCanvas(cropWidth, cropHeight, cropAngle, OcrRefinement.MAX_CROP_PIXELS)
                val sourceToCrop = OcrRefinement.compose(cropPlan.sourceToCanvas, toCrop)
                val cropped = Bitmap.createBitmap(cropPlan.width, cropPlan.height, Bitmap.Config.ARGB_8888)
                try {
                    cropped.eraseColor(Color.WHITE)
                    Canvas(cropped).apply {
                        concat(sourceToCrop.androidMatrix())
                        clipRect(crop.left, crop.top, crop.right, crop.bottom)
                        drawBitmap(working, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                    val cropToSource = sourceToCrop.inverse()
                    val candidate = runOcr(cropped).map { it.mapped(cropToSource) }
                        .filter { OcrRefinement.betterLine(line, it) }
                        .maxByOrNull { OcrRefinement.meanConfidence(it.words) ?: 0f }
                    if (candidate != null) {
                        // Replace, never append: expanded crops can also see adjacent lines.
                        refined[index] = candidate.copy(block = line.block, line = line.line)
                        improved++
                    }
                } catch (_: MlKitException) {
                    warnings += "A focused text retry failed; the original line was kept."
                } finally { cropped.recycle() }
            }
            val transform = OcrRefinement.compose(bitmapToPage, workingToBitmap)
            val elements = refined.flatMap { line -> line.words.mapNotNull { word ->
                val mapped = word.mapped(transform).box
                val box = Box(mapped.left.coerceIn(0f, width), mapped.top.coerceIn(0f, height),
                    mapped.right.coerceIn(0f, width), mapped.bottom.coerceIn(0f, height))
                if (box.width <= 0f || box.height <= 0f) null else TextElement(word.text, listOf(box), line.block, line.line)
            } }
            if (elements.any { it.text.any { c -> c in "=∫∑√" } }) warnings += "Possible equations or tables: verify reading order or skip this page."
            if (elements.isEmpty()) warnings += "No readable text found. This may be blank or need a different scan rotation."
            if (refined.any { line -> line.words.any { it.confidence != null && it.confidence < .6f } }) {
                warnings += "Some words are uncertain after OCR; compare them with the original image."
            }
            val method = buildString {
                append("Latin OCR")
                if (deskewed) append("; straightened")
                if (improved > 0) append("; focused retries")
            }
            return ExtractedPage(elements, width, height, method, warnings.distinct(), transform)
        } finally { if (working !== bitmap) working.recycle() }
    }
    suspend fun render(path: String, index: Int, targetWidth: Int = 1400): Bitmap {
        var owned: Bitmap? = null
        try {
            return withContext(Dispatchers.IO) { renderLock.withLock {
                if (!path.endsWith(".pdf")) return@withLock ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { d, info, _ ->
                    val ratio = minOf(1f, targetWidth.toFloat() / info.size.width)
                    d.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1)); d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.also { owned = it }
                openPdf(path).use { renderer -> renderer.openPage(index).use { page ->
                    val scale = minOf(targetWidth.toFloat() / page.width, sqrt(3_000_000f / (page.width.toFloat() * page.height)))
                    Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                        owned = it
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                } }
            } }
        } catch (error: Throwable) {
            owned?.recycle()
            throw error
        }
    }
}
