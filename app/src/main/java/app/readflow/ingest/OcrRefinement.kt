package app.readflow.ingest

import app.readflow.core.Box
import app.readflow.core.Transform
import kotlin.math.*

/** Pure geometry and conservative decisions shared by full-page and focused OCR passes. */
internal data class OcrWord(
    val text: String,
    val box: Box,
    val confidence: Float?,
    val corners: List<Pair<Float, Float>> = listOf(box.left to box.top, box.right to box.top,
        box.right to box.bottom, box.left to box.bottom),
) {
    fun mapped(transform: Transform): OcrWord {
        val points = corners.map { transform.map(it.first, it.second) }
        return copy(box = OcrRefinement.bounds(points), corners = points)
    }
}

internal data class OcrLine(val block: Int, val line: Int, val words: List<OcrWord>, val angle: Float) {
    val box: Box get() = words.map { it.box }.reduce(Box::union)
    val text: String get() = words.joinToString(" ") { it.text }
    val characters: Int get() = words.sumOf { it.text.count(Char::isLetterOrDigit) }
    fun mapped(transform: Transform) = copy(words = words.map { it.mapped(transform) })
}

internal data class OcrCanvas(val width: Int, val height: Int, val sourceToCanvas: Transform)

internal object OcrRefinement {
    const val MAX_SOURCE_PIXELS = 10_000_000
    const val MAX_ROTATED_PIXELS = 12_000_000
    const val MAX_CROP_PIXELS = 2_000_000
    const val MAX_LINE_RETRIES = 6

    // This app pins the bundled recognizer: its [0, 1] score includes genuine zero
    // confidence. The unavailable-zero caveat applies to older unbundled runtimes.
    fun confidence(raw: Float): Float? = raw.takeIf { it.isFinite() && it >= 0f && it <= 1f }

    fun bounds(points: List<Pair<Float, Float>>) = Box(points.minOf { it.first }, points.minOf { it.second },
        points.maxOf { it.first }, points.maxOf { it.second })

    /** outer(inner(point)), including translation, rotation and unequal X/Y scaling. */
    fun compose(outer: Transform, inner: Transform) = Transform(
        outer.a * inner.a + outer.c * inner.b, outer.b * inner.a + outer.d * inner.b,
        outer.a * inner.c + outer.c * inner.d, outer.b * inner.c + outer.d * inner.d,
        outer.a * inner.tx + outer.c * inner.ty + outer.tx,
        outer.b * inner.tx + outer.d * inner.ty + outer.ty,
    )

    fun fitScale(width: Int, height: Int, maxPixels: Int = MAX_SOURCE_PIXELS): Float {
        require(width > 0 && height > 0)
        return minOf(1.0, sqrt(maxPixels.toDouble() / (width.toDouble() * height))).toFloat()
    }

    fun rotatedCanvas(width: Int, height: Int, degrees: Float, maxPixels: Int = MAX_ROTATED_PIXELS): OcrCanvas {
        val radians = Math.toRadians(degrees.toDouble())
        val rotation = Transform(cos(radians).toFloat(), sin(radians).toFloat(),
            -sin(radians).toFloat(), cos(radians).toFloat())
        val bounds = rotation.map(Box(0f, 0f, width.toFloat(), height.toFloat()))
        val rawWidth = ceil(bounds.width.toDouble()).toInt().coerceAtLeast(1)
        val rawHeight = ceil(bounds.height.toDouble()).toInt().coerceAtLeast(1)
        val scale = fitScale(rawWidth, rawHeight, maxPixels)
        return OcrCanvas((rawWidth * scale).toInt().coerceAtLeast(1), (rawHeight * scale).toInt().coerceAtLeast(1),
            compose(Transform(scale, 0f, 0f, scale, -bounds.left * scale, -bounds.top * scale), rotation))
    }

    fun deskewAngle(lines: List<OcrLine>): Float? {
        val angles = lines.filter { it.characters >= 8 && it.box.width >= it.box.height * 3 &&
            it.angle.isFinite() && abs(it.angle) <= 12f }.map { it.angle }.sorted()
        if (angles.size < 3) return null
        val median = angles[angles.size / 2]
        // A single sloping heading or diagram must not turn an otherwise upright page.
        if (abs(median) < .8f || angles.count { abs(it - median) <= 1.5f } < ceil(lines.size * .7).toInt()) return null
        return -median
    }

    fun meanConfidence(words: List<OcrWord>): Float? {
        val total = words.sumOf { it.text.length.coerceAtLeast(1) }
        val known = words.filter { it.confidence != null }
        val knownWeight = known.sumOf { it.text.length.coerceAtLeast(1) }
        if (total == 0 || knownWeight < total * .75f) return null
        return (known.sumOf { it.confidence!!.toDouble() * it.text.length.coerceAtLeast(1) } / knownWeight).toFloat()
    }

    private fun suspectCharacters(words: List<OcrWord>) = words.sumOf { word ->
        word.text.count { it == '\uFFFD' || it == '|' || (it.isISOControl() && !it.isWhitespace()) }
    }

    fun retryPriority(line: OcrLine): Float {
        val minimum = line.words.mapNotNull { it.confidence }.minOrNull()
        val heights = line.words.filter { it.text.any(Char::isLetterOrDigit) }.map { it.box.height }.sorted()
        val small = heights.isNotEmpty() && heights[heights.size / 2] < 20f
        return (if (minimum != null && minimum < .82f) 2f + 1f - minimum else 0f) +
            (if (small) 1f else 0f) + suspectCharacters(line.words) * 2f
    }

    fun retryIndices(lines: List<OcrLine>): List<Int> = lines.indices.filter { retryPriority(lines[it]) > 0f }
        .sortedByDescending { retryPriority(lines[it]) }.take(MAX_LINE_RETRIES)

    fun cropBounds(line: OcrLine, width: Int, height: Int): Box {
        val box = line.box
        val padding = maxOf(4f, box.height * .35f)
        return Box(floor(maxOf(0f, box.left - padding)), floor(maxOf(0f, box.top - padding)),
            ceil(minOf(width.toFloat(), box.right + padding)), ceil(minOf(height.toFloat(), box.bottom + padding)))
    }

    fun cropScale(line: OcrLine, crop: Box): Float {
        val heights = line.words.filter { it.text.any(Char::isLetterOrDigit) }.map { it.box.height }.sorted()
        val medianHeight = heights.getOrNull(heights.size / 2) ?: 24f
        // Enlarging cannot restore missing detail, but gives small glyphs a useful model
        // input size. Keep already large type at its native size and bound memory.
        return minOf((32f / medianHeight.coerceAtLeast(1f)).coerceIn(1f, 2f),
            sqrt(MAX_CROP_PIXELS / (crop.width * crop.height).coerceAtLeast(1f)))
    }

    private fun overlap(a: Box, b: Box): Float = a.intersection(b) /
        (a.width * a.height).coerceAtLeast(1f)

    /** Both passes must refer to the same line in the same image coordinates. */
    fun betterLine(original: OcrLine, candidate: OcrLine): Boolean {
        if (candidate.words.isEmpty() || original.words.isEmpty() || candidate.text == original.text) return false
        if (overlap(original.box, candidate.box) < .7f || overlap(candidate.box, original.box) < .7f) return false
        if (candidate.characters < original.characters * .8f || candidate.characters > original.characters * 1.25f + 1) return false
        if (abs(candidate.words.size - original.words.size) > maxOf(1, original.words.size / 4)) return false
        // Every original word must still have geometry. A high-confidence neighboring
        // line, or a crop that simply drops the difficult name, is not an improvement.
        if (original.words.any { old -> candidate.words.none { overlap(old.box, it.box) >= .4f } }) return false
        if (candidate.words.any { next -> original.words.none { overlap(next.box, it.box) >= .4f } }) return false
        if (original.words.any { old -> (old.confidence ?: 0f) >= .94f &&
            candidate.words.none { it.text == old.text && overlap(old.box, it.box) >= .4f } }) return false
        if (suspectCharacters(candidate.words) > suspectCharacters(original.words)) return false
        val before = meanConfidence(original.words)
        val after = meanConfidence(candidate.words) ?: return false
        return if (before != null) after >= before + .06f else
            after >= .9f && suspectCharacters(candidate.words) < suspectCharacters(original.words)
    }

    fun keepDeskewed(original: List<OcrLine>, candidateInOriginal: List<OcrLine>): Boolean {
        if (original.isEmpty() || candidateInOriginal.isEmpty()) return false
        val oldWords = original.flatMap { it.words }
        val newWords = candidateInOriginal.flatMap { it.words }
        if (newWords.size < oldWords.size || newWords.size > oldWords.size * 1.15f + 1) return false
        if (oldWords.any { old -> newWords.none { next -> overlap(old.box, next.box) >= .4f &&
                ((old.confidence ?: 0f) < .94f || old.text == next.text) } }) return false
        if (suspectCharacters(newWords) > suspectCharacters(oldWords)) return false
        val before = meanConfidence(oldWords)
        val after = meanConfidence(newWords)
        val sameText = oldWords.map { it.text } == newWords.map { it.text }
        return sameText && (before == null || after != null && after >= before - .01f) ||
            before != null && after != null && after >= before + .02f
    }
}
