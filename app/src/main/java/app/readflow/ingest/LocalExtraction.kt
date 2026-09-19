package app.readflow.ingest

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.readflow.core.*
import com.google.mlkit.vision.common.InputImage
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
                val scale = minOf(225f / 72f, sqrt(6_000_000f / (pdf.width.toFloat() * pdf.height)))
                val bitmap = Bitmap.createBitmap(maxOf(1, (pdf.width * scale).roundToInt()), maxOf(1, (pdf.height * scale).roundToInt()), Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    pdf.render(bitmap, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val result = recognize(bitmap, rotation, Transform(1 / scale, 0f, 0f, 1 / scale), pdf.width.toFloat(), pdf.height.toFloat())
                    result.copy(method = if (hasImages && usable) "Mixed page: full-page OCR" else "Latin OCR",
                        warnings = result.warnings + if (usable) listOf("OCR supplies word geometry on this page; check recognition against the original.") else emptyList())
                } finally { bitmap.recycle() }
            } }
        }
    }
    private suspend fun extractImage(path: String, rotation: Int): ExtractedPage {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
            val scale = minOf(1.0, sqrt(6_000_000.0 / (info.size.width.toDouble() * info.size.height)))
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return try { recognize(bitmap, rotation, Transform(), bitmap.width.toFloat(), bitmap.height.toFloat()) } finally { bitmap.recycle() }
    }
    private suspend fun recognize(bitmap: Bitmap, rotation: Int, bitmapToPage: Transform, width: Float, height: Float): ExtractedPage {
        val rotationMatrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = if (rotation == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, rotationMatrix, true)
        val processedToBitmap = when ((rotation % 360 + 360) % 360) {
            90 -> Transform(0f, -1f, 1f, 0f, 0f, bitmap.height.toFloat())
            180 -> Transform(-1f, 0f, 0f, -1f, bitmap.width.toFloat(), bitmap.height.toFloat())
            270 -> Transform(0f, 1f, -1f, 0f, bitmap.width.toFloat(), 0f)
            else -> Transform()
        }
        try {
            // ML Kit's Task cannot be interrupted safely. Hold the bitmap/lock until it completes.
            val result = withContext(NonCancellable) { recognizer.process(InputImage.fromBitmap(rotated, 0)).await() }
            currentCoroutineContext().ensureActive()
            val elements = result.textBlocks.flatMapIndexed { block, textBlock -> textBlock.lines.flatMapIndexed { line, textLine ->
                textLine.elements.mapNotNull { element -> element.boundingBox?.let { b ->
                    val box = bitmapToPage.map(processedToBitmap.map(Box(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())))
                    TextElement(element.text, listOf(box), block, line)
                } }
            } }
            val warnings = mutableListOf<String>()
            if (elements.any { it.text.any { c -> c in "=∫∑√" } }) warnings += "Possible equations or tables: verify reading order or skip this page."
            if (elements.isEmpty()) warnings += "No readable text found. This may be blank or need a different scan rotation."
            // Composition retains a reversible processed-image-to-page mapping.
            val origin = bitmapToPage.map(processedToBitmap.tx, processedToBitmap.ty)
            val transform = Transform(bitmapToPage.a * processedToBitmap.a, bitmapToPage.d * processedToBitmap.b,
                bitmapToPage.a * processedToBitmap.c, bitmapToPage.d * processedToBitmap.d, origin.first, origin.second)
            return ExtractedPage(elements, width, height, "Latin OCR", warnings, transform)
        } finally { if (rotated !== bitmap) rotated.recycle() }
    }
    suspend fun render(path: String, index: Int, targetWidth: Int = 1400): Bitmap = withContext(Dispatchers.IO) {
        resourceLock.withLock {
            if (!path.endsWith(".pdf")) return@withLock ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { d, info, _ ->
                val ratio = minOf(1f, targetWidth.toFloat() / info.size.width)
                d.setTargetSize((info.size.width * ratio).toInt(), (info.size.height * ratio).toInt()); d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            openPdf(path).use { renderer -> renderer.openPage(index).use { page ->
                val scale = minOf(targetWidth.toFloat() / page.width, sqrt(3_000_000f / (page.width.toFloat() * page.height)))
                Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } }
        }
    }
}
