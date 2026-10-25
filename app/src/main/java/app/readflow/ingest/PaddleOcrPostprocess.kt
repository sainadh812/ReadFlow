package app.readflow.ingest

import app.readflow.core.Box
import app.readflow.core.TextElement
import java.text.Normalizer
import kotlin.math.*

/** DB/CTC model contracts follow PaddleOCR's Apache-2.0 reference deployment.
 * This bounded Kotlin implementation avoids a second native image-processing runtime.
 * See docs/PADDLE_OCR.md for pinned sources and deliberate differences. */
internal data class PaddlePoint(val x: Float, val y: Float)
internal data class PaddleQuad(val points: List<PaddlePoint>, val confidence: Float) {
    val bounds get() = Box(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
}
internal data class PaddleText(val text: String, val confidence: Float)

internal object PaddleOcrPostprocess {
    const val MAX_SIDE = 1280
    const val MAX_REGIONS = 1000
    private val tokens = Regex("\\S+")

    /** Parse only the verified model's simple YAML scalar dictionary, without accepting arbitrary YAML. */
    fun dictionary(yaml: String): List<String> {
        require(yaml.length <= 64_000)
        val section = yaml.substringAfter("  character_dict:\n", "")
        require(section.isNotEmpty()) { "PaddleOCR dictionary is missing" }
        val characters = section.lineSequence().filter { it.isNotBlank() }.map { line ->
            require(line.startsWith("  - ")) { "Unexpected PaddleOCR dictionary format" }
            val raw = line.substring(4)
            val value = if (raw.startsWith("'")) {
                require(raw.endsWith("'") && raw.length >= 2)
                raw.substring(1, raw.length - 1).replace("''", "'")
            } else raw
            require(value.codePointCount(0, value.length) == 1) { "Invalid PaddleOCR dictionary entry" }
            value
        }.toList()
        // Paddle's CTCLabelDecode inserts blank at 0 and appends space to character_dict.
        require(characters.size == 836) { "Unexpected PaddleOCR vocabulary size" }
        return listOf("") + characters + " "
    }

    fun decode(probabilities: FloatArray, steps: Int, classes: Int, dictionary: List<String>): PaddleText {
        require(steps in 1..4096 && classes == dictionary.size && probabilities.size == steps * classes)
        val text = StringBuilder(); var previous = -1; var confidence = 0.0; var kept = 0
        for (step in 0 until steps) {
            var best = 0; var score = Float.NEGATIVE_INFINITY
            for (index in 0 until classes) {
                val p = probabilities[step * classes + index]
                require(p.isFinite()) { "PaddleOCR returned invalid probabilities" }
                if (p > score) { score = p; best = index }
            }
            if (best != 0 && best != previous) {
                text.append(dictionary[best]); confidence += score; kept++
            }
            previous = best
        }
        return PaddleText(Normalizer.normalize(text.toString().trim(), Normalizer.Form.NFC),
            if (kept > 0) (confidence / kept).toFloat().coerceIn(0f, 1f) else 0f)
    }

    fun detectorSize(width: Int, height: Int, limit: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && limit in 32..MAX_SIDE)
        val scale = min(1.0, limit.toDouble() / max(width, height))
        return ((width * scale / 32).roundToInt().coerceAtLeast(1) * 32).coerceAtMost(limit) to
            ((height * scale / 32).roundToInt().coerceAtLeast(1) * 32).coerceAtMost(limit)
    }

    /** Threshold -> connected components -> convex hull -> minimum-area rectangle -> DB unclip.
     * Scores include the whole rotated rectangle, not only above-threshold pixels. */
    fun detect(probabilities: FloatArray, width: Int, height: Int, sourceWidth: Int, sourceHeight: Int,
        checkCancelled: () -> Unit = {}): List<PaddleQuad> {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE && probabilities.size == width * height)
        require(sourceWidth > 0 && sourceHeight > 0)
        require(probabilities.all { it.isFinite() }) { "PaddleOCR returned an invalid detection map" }
        val visited = BooleanArray(probabilities.size)
        val queue = IntArray(probabilities.size)
        val boxes = mutableListOf<PaddleQuad>()
        var components = 0
        for (start in probabilities.indices) {
            if (start % (width * 16) == 0) checkCancelled()
            if (visited[start] || probabilities[start] <= .3f) continue
            var head = 0; var tail = 1; queue[0] = start; visited[start] = true
            val boundary = mutableListOf<PaddlePoint>()
            while (head < tail) {
                if (head % 4096 == 0) checkCancelled()
                val index = queue[head++]; val x = index % width; val y = index / width
                var edge = false
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) { edge = true; continue }
                    val next = ny * width + nx
                    if (probabilities[next] <= .3f) { edge = true; continue }
                    if (!visited[next]) { visited[next] = true; queue[tail++] = next }
                }
                if (edge) boundary += PaddlePoint(x.toFloat(), y.toFloat())
            }
            if (tail < 6 || boundary.size < 3) continue
            check(++components <= MAX_REGIONS) { "PaddleOCR found too many text regions; try a page crop" }
            val rect = minimumRectangle(hull(boundary)) ?: continue
            if (min(rect.width, rect.height) < 3f) continue
            val score = rectangleScore(probabilities, width, height, rect)
            if (score < .6f) continue
            val distance = rect.width * rect.height * 1.5f / (2 * (rect.width + rect.height))
            val corners = rect.corners(distance).map { p -> PaddlePoint(
                (p.x * sourceWidth / width).coerceIn(0f, sourceWidth.toFloat()),
                (p.y * sourceHeight / height).coerceIn(0f, sourceHeight.toFloat())) }
            val sorted = corners.sortedBy { it.x }
            val left = sorted.take(2).sortedBy { it.y }; val right = sorted.takeLast(2).sortedBy { it.y }
            val quad = PaddleQuad(listOf(left[0], right[0], right[1], left[1]), score)
            if (quad.bounds.width > 3 && quad.bounds.height > 3) boxes += quad
        }
        return boxes.sortedBy { it.bounds.top }
    }

    /** Reuse only observed ML Kit geometry where words agree exactly, without accent folding.
     * Repeated identical words are matched in order only when the entire line token sequence agrees. */
    fun words(text: String, quad: PaddleQuad, references: List<TextElement>, block: Int,
        usedReferences: MutableSet<TextElement> = mutableSetOf()): List<TextElement> {
        val words = tokens.findAll(text).map { it.value }.toList()
        val candidates = references.filter { ref -> ref !in usedReferences && ref.boxes.isNotEmpty() && ref.boxes.all {
            it.width > 0 && it.height > 0 && quad.bounds.intersection(it) >= it.width * it.height * .75f
        } }.sortedBy { it.boxes.minOf { box -> box.left } }
        fun key(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC)
        val identical = words.map(::key) == candidates.map { key(it.text) }
        val counts = words.groupingBy(::key).eachCount()
        return words.mapIndexed { index, word ->
            val match = if (identical) candidates[index] else candidates.singleOrNull { key(it.text) == key(word) && counts[key(word)] == 1 }
            if (match != null) usedReferences += match
            TextElement(word, match?.boxes.orEmpty(), block, block)
        }
    }

    private fun cross(a: PaddlePoint, b: PaddlePoint, c: PaddlePoint): Float =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    private fun hull(points: List<PaddlePoint>): List<PaddlePoint> {
        val sorted = points.distinct().sortedWith(compareBy<PaddlePoint> { it.x }.thenBy { it.y })
        if (sorted.size < 3) return sorted
        val lower = mutableListOf<PaddlePoint>(); val upper = mutableListOf<PaddlePoint>()
        for (p in sorted) { while (lower.size >= 2 && cross(lower[lower.lastIndex - 1], lower.last(), p) <= 0) lower.removeAt(lower.lastIndex); lower += p }
        for (p in sorted.asReversed()) { while (upper.size >= 2 && cross(upper[upper.lastIndex - 1], upper.last(), p) <= 0) upper.removeAt(upper.lastIndex); upper += p }
        return lower.dropLast(1) + upper.dropLast(1)
    }
    private data class Rectangle(val ux: Float, val uy: Float, val minU: Float, val maxU: Float, val minV: Float, val maxV: Float) {
        val width get() = maxU - minU
        val height get() = maxV - minV
        fun corners(padding: Float = 0f): List<PaddlePoint> = listOf(
            minU - padding to minV - padding, maxU + padding to minV - padding,
            maxU + padding to maxV + padding, minU - padding to maxV + padding,
        ).map { (u, v) -> PaddlePoint(u * ux - v * uy, u * uy + v * ux) }
    }
    private fun minimumRectangle(hull: List<PaddlePoint>): Rectangle? {
        var best: Rectangle? = null; var area = Float.POSITIVE_INFINITY
        for (i in hull.indices) {
            val a = hull[i]; val b = hull[(i + 1) % hull.size]; val length = hypot(b.x - a.x, b.y - a.y)
            if (length < .001f) continue
            val ux = (b.x - a.x) / length; val uy = (b.y - a.y) / length
            var minU = Float.POSITIVE_INFINITY; var maxU = Float.NEGATIVE_INFINITY
            var minV = Float.POSITIVE_INFINITY; var maxV = Float.NEGATIVE_INFINITY
            for (p in hull) { val u = p.x * ux + p.y * uy; val v = -p.x * uy + p.y * ux
                minU = min(minU, u); maxU = max(maxU, u); minV = min(minV, v); maxV = max(maxV, v) }
            val candidate = Rectangle(ux, uy, minU, maxU, minV, maxV)
            if (candidate.width * candidate.height < area) { best = candidate; area = candidate.width * candidate.height }
        }
        return best
    }
    private fun rectangleScore(map: FloatArray, width: Int, height: Int, rect: Rectangle): Float {
        val points = rect.corners()
        val left = floor(points.minOf { it.x }).toInt().coerceIn(0, width - 1)
        val right = ceil(points.maxOf { it.x }).toInt().coerceIn(0, width - 1)
        val top = floor(points.minOf { it.y }).toInt().coerceIn(0, height - 1)
        val bottom = ceil(points.maxOf { it.y }).toInt().coerceIn(0, height - 1)
        var sum = 0.0; var count = 0
        for (y in top..bottom) for (x in left..right) {
            val u = x * rect.ux + y * rect.uy; val v = -x * rect.uy + y * rect.ux
            if (u >= rect.minU - .01f && u <= rect.maxU + .01f && v >= rect.minV - .01f && v <= rect.maxV + .01f) { sum += map[y * width + x]; count++ }
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }
}
