package app.readflow.core

import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale
import kotlinx.serialization.Serializable

const val PIPELINE_VERSION = "readflow-1"
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun stableId(vararg parts: Any): String = sha256(parts.joinToString("\u0000").toByteArray()).take(32)

@Serializable data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom
    fun union(other: Box) = Box(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
    fun intersection(other: Box): Float = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f) * (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
}

@Serializable data class Transform(val a: Float = 1f, val b: Float = 0f, val c: Float = 0f, val d: Float = 1f, val tx: Float = 0f, val ty: Float = 0f) {
    fun map(x: Float, y: Float) = (a * x + c * y + tx) to (b * x + d * y + ty)
    fun inverse(): Transform {
        val det = a * d - b * c
        require(kotlin.math.abs(det) > 0.000001f)
        return Transform(d / det, -b / det, -c / det, a / det, (c * ty - d * tx) / det, (b * tx - a * ty) / det)
    }
    fun map(box: Box): Box {
        val corners = listOf(map(box.left, box.top), map(box.right, box.top), map(box.left, box.bottom), map(box.right, box.bottom))
        return Box(corners.minOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.first }, corners.maxOf { it.second })
    }
}

@Serializable data class SourceWord(
    val id: String, val text: String, val sourceStart: Int, val sourceEnd: Int,
    val start: Int, val end: Int, val order: Int, val paragraphId: String, val sentenceId: String,
    val boxes: List<Box> = emptyList(), val marginal: Boolean = false, val line: Int = 0,
)
@Serializable data class LayoutLine(val index: Int, val text: String, val boxes: List<Box>)
@Serializable data class LayoutBlock(val index: Int, val lines: List<LayoutLine>)
@Serializable data class Paragraph(val id: String, val order: Int, val original: String, val start: Int, val end: Int, val heading: Boolean = false)
@Serializable data class PageContent(
    val id: String, val documentId: String, val index: Int, val original: String, val reading: String,
    val words: List<SourceWord>, val paragraphs: List<Paragraph>, val width: Float, val height: Float,
    val extraction: String, val warnings: List<String> = emptyList(), val imageToPage: Transform = Transform(),
    val layout: List<LayoutBlock> = emptyList(),
)
@Serializable data class SpokenToken(val text: String, val sourceIds: List<String>)
@Serializable data class Voice(val id: String, val name: String)
@Serializable data class SpeechText(val text: String, val tokens: List<SpokenToken>, val sourceIds: List<String>)
@Serializable data class WordTiming(val wordId: String, val startSample: Long, val endSample: Long, val confidence: Float, val method: String = "ctc-known-transcript-v1")
@Serializable data class AlignedAudio(
    val key: String, val audioPath: String, val audioSha256: String, val sampleRate: Int, val sampleCount: Long,
    val timings: List<WordTiming>, val sourceIds: List<String>, val speech: SpeechText,
    val model: String, val voice: String, val alignmentVersion: String,
) { val durationMs get() = sampleCount * 1000 / sampleRate }
data class PcmAudio(val samples: FloatArray, val sampleRate: Int)
data class TextElement(val text: String, val boxes: List<Box>, val block: Int = 0, val line: Int = 0, val heading: Boolean = false)
data class ExtractedPage(val elements: List<TextElement>, val width: Float, val height: Float, val method: String, val warnings: List<String> = emptyList(), val transform: Transform = Transform())

interface PageExtractor { suspend fun extract(path: String, page: Int, rotation: Int = 0): ExtractedPage }
interface ReadingOrder { fun order(elements: List<TextElement>, width: Float): List<TextElement> }
interface TextNormalizer { fun normalize(words: List<SourceWord>): SpeechText }
interface SpeechAligner { suspend fun align(audio: PcmAudio, text: SpeechText): List<WordTiming> }
interface TtsEngine {
    val timingCapability: String
    val cancellationCapability: String
    fun voices(pack: String): List<Voice>
    suspend fun load(pack: String, voice: String)
    suspend fun synthesize(text: String, isCurrent: () -> Boolean): PcmAudio
    suspend fun unload()
}

class GeometricReadingOrder : ReadingOrder {
    override fun order(elements: List<TextElement>, width: Float): List<TextElement> {
        if (elements.isEmpty() || elements.any { it.boxes.isEmpty() }) return elements
        val grouped = elements.groupBy { it.block to it.line }.values.map { line -> line.sortedBy { it.boxes.first().left } }
        val boxes = grouped.map { line -> line.flatMap { it.boxes }.reduce(Box::union) }
        // Only accept a clear central gutter shared by several lines on BOTH sides.
        val mid = width / 2
        val left = boxes.count { it.right < mid - width * .02f }
        val right = boxes.count { it.left > mid + width * .02f }
        val crossing = boxes.count { it.left < mid && it.right > mid }
        val columns = left >= 3 && right >= 3 && crossing <= 2
        return grouped.indices.sortedWith(compareBy<Int> {
            if (!columns) 0 else if (boxes[it].width > width * .7f && boxes[it].top < boxes.minOf { b -> b.top } + 40) -1 else if (boxes[it].left < mid) 0 else 1
        }.thenBy { boxes[it].top }.thenBy { boxes[it].left }).flatMap { grouped[it] }
    }
}

class DocumentPipeline(private val ordering: ReadingOrder = GeometricReadingOrder()) {
    fun process(documentId: String, page: Int, extracted: ExtractedPage): PageContent {
        val pageId = stableId(documentId, PIPELINE_VERSION, page)
        val ordered = ordering.order(extracted.elements, extracted.width)
        val original = StringBuilder()
        val reading = StringBuilder()
        val words = mutableListOf<SourceWord>()
        val paragraphs = mutableListOf<Paragraph>()
        val groups = ordered.groupBy { it.block }.values
        for ((paragraphOrder, group) in groups.withIndex()) {
            if (reading.isNotEmpty()) reading.append("\n\n")
            val start = reading.length
            val originalStart = original.length
            val paragraphId = stableId(pageId, "p", paragraphOrder)
            var previousLine = -1
            for (element in group) {
                if (original.isNotEmpty()) original.append(if (previousLine != element.line) '\n' else ' ')
                val sourceStart = original.length
                original.append(element.text)
                if (reading.length > start) reading.append(' ')
                val tokenStart = reading.length
                reading.append(element.text.trim())
                // Native multiword runs with no exact word geometry are supplied by OCR instead.
                Regex("\\S+").findAll(element.text.trim()).forEach { token ->
                    val id = stableId(pageId, "w", sourceStart + token.range.first, sourceStart + token.range.last + 1)
                    words += SourceWord(id, token.value, sourceStart + token.range.first, sourceStart + token.range.last + 1,
                        tokenStart + token.range.first, tokenStart + token.range.last + 1, words.size, paragraphId, "",
                        if (element.text.trim() == token.value) element.boxes else emptyList(),
                        element.boxes.any { it.top < extracted.height * .055f || it.bottom > extracted.height * .95f }, element.line)
                }
                previousLine = element.line
            }
            paragraphs += Paragraph(paragraphId, paragraphOrder, original.substring(originalStart), start, reading.length, group.any { it.heading })
        }
        val iterator = BreakIterator.getSentenceInstance(Locale.US).apply { setText(reading.toString()) }
        var begin = iterator.first()
        var end = iterator.next()
        val sentenceWords = words.toMutableList()
        var sentence = 0
        while (end != BreakIterator.DONE) {
            for (i in words.indices) if (words[i].start in begin until end) sentenceWords[i] = words[i].copy(sentenceId = stableId(pageId, "s", sentence))
            val span = reading.substring(begin, end).trim()
            begin = end; end = iterator.next()
            if (!Regex(".*\\b(Dr|Mr|Mrs|Ms|Prof|St)\\.$").matches(span)) sentence++
        }
        return PageContent(pageId, documentId, page, original.toString(), reading.toString(), sentenceWords, paragraphs,
            extracted.width, extracted.height, extracted.method, extracted.warnings, extracted.transform,
            extracted.elements.groupBy { it.block }.map { (block, elements) -> LayoutBlock(block, elements.groupBy { it.line }.map { (line, tokens) -> LayoutLine(line, tokens.joinToString(" ") { it.text }, tokens.flatMap { it.boxes }) }) })
    }
}
