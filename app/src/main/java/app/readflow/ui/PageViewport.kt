package app.readflow.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/** Geometry in display orientation; the rendered PDF and hit targets use the same offset. */
internal data class PageViewport(val pageWidth: Float, val pageHeight: Float,
    val viewWidth: Float, val viewHeight: Float, val fitWidth: Boolean) {
    val fit = (if (fitWidth) viewWidth / pageWidth
        else minOf(viewWidth / pageWidth, viewHeight / pageHeight)).coerceAtLeast(.001f)
    val base = Offset((viewWidth - pageWidth * fit) / 2, ((viewHeight - pageHeight * fit) / 2).coerceAtLeast(0f))

    fun constrain(pan: Offset, zoom: Float): Offset {
        fun axis(candidate: Float, base: Float, viewport: Float, content: Float): Float =
            (if (content <= viewport) (viewport - content) / 2 else (base + candidate).coerceIn(viewport - content, 0f)) - base
        return Offset(axis(pan.x, base.x, viewWidth, pageWidth * fit * zoom),
            axis(pan.y, base.y, viewHeight, pageHeight * fit * zoom))
    }

    fun zoomPan(pan: Offset, oldZoom: Float, newZoom: Float, centroid: Offset, delta: Offset): Offset =
        constrain((pan + base - centroid) * (newZoom / oldZoom) + centroid - base + delta, newZoom)
}

/** One page per completed single-finger swipe; pinches and vertical scrolling never turn pages. */
internal fun swipePageDelta(travel: Offset, threshold: Float, multiTouch: Boolean, zoom: Float,
    enabled: Boolean, rightAdvances: Boolean): Int {
    if (!enabled || multiTouch || zoom > 1.01f || abs(travel.x) < threshold || abs(travel.x) < abs(travel.y) * 1.5f) return 0
    val direction = if (travel.x > 0) 1 else -1
    return if (rightAdvances) direction else -direction
}
