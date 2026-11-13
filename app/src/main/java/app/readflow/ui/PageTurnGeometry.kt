package app.readflow.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.sin

/** A page is folded about the perpendicular bisector of its fixed and dragged corners. */
internal data class PageFold(val front: List<Offset>, val back: List<Offset>, val normal: Offset,
    val intercept: Float, val crease: List<Offset>) {
    fun reflect(point: Offset): Offset {
        val lengthSquared = normal.x * normal.x + normal.y * normal.y
        if (lengthSquared < .001f) return point
        return point - normal * (2f * (point.x * normal.x + point.y * normal.y - intercept) / lengthSquared)
    }
}

internal fun pageFold(width: Float, height: Float, progress: Float, towardLeft: Boolean): PageFold {
    val p = progress.coerceIn(.0001f, .9999f)
    val corner = Offset(if (towardLeft) width else 0f, height)
    val lift = minOf(height * .18f, width * .32f) * sin(Math.PI.toFloat() * p)
    val finger = Offset(corner.x + (if (towardLeft) -1 else 1) * 2f * width * p, height - lift)
    val normal = corner - finger
    val intercept = (corner.x * corner.x + corner.y * corner.y - finger.x * finger.x - finger.y * finger.y) / 2f
    val rectangle = listOf(Offset.Zero, Offset(width, 0f), Offset(width, height), Offset(0f, height))
    fun distance(point: Offset) = point.x * normal.x + point.y * normal.y - intercept
    fun clip(keepFront: Boolean): List<Offset> = buildList {
        rectangle.indices.forEach { index ->
            val start = rectangle[index]
            val end = rectangle[(index + 1) % rectangle.size]
            val a = distance(start)
            val b = distance(end)
            val insideA = if (keepFront) a <= 0 else a >= 0
            val insideB = if (keepFront) b <= 0 else b >= 0
            if (insideA) add(start)
            if (insideA != insideB) add(start + (end - start) * (a / (a - b)))
        }
    }
    val crease = buildList {
        rectangle.indices.forEach { index ->
            val start = rectangle[index]
            val end = rectangle[(index + 1) % rectangle.size]
            val a = distance(start)
            val b = distance(end)
            if ((a <= 0f && b >= 0f || a >= 0f && b <= 0f) && abs(a - b) > .001f) {
                add(start + (end - start) * (a / (a - b)))
            }
        }
    }
    val fold = PageFold(clip(true), emptyList(), normal, intercept, crease)
    return fold.copy(back = clip(false).map(fold::reflect))
}

/** Physical left/right is independent of locale; the book preference controls page order. */
internal fun pageTurnTarget(index: Int, count: Int, delta: Int): Int? =
    (index + delta).takeIf { abs(delta) == 1 && it in 0 until count }
