package app.readflow.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Lock to the initial drag direction; a vertical scroll must not become a page turn. */
internal class PageSwipeGesture(private val touchSlop: Float) {
    private enum class Axis { PENDING, HORIZONTAL, VERTICAL }
    private var axis = Axis.PENDING
    private var multiTouch = false
    private var travel = Offset.Zero

    val horizontal: Boolean get() = axis == Axis.HORIZONTAL && !multiTouch

    fun move(delta: Offset, pointerCount: Int) {
        multiTouch = multiTouch || pointerCount > 1
        travel += delta
        if (axis == Axis.PENDING && travel.getDistance() > touchSlop) {
            axis = if (abs(travel.x) >= abs(travel.y) * 1.5f) Axis.HORIZONTAL else Axis.VERTICAL
        }
    }

    fun pageDelta(threshold: Float, enabled: Boolean, rightAdvances: Boolean, zoom: Float = 1f): Int =
        if (horizontal) swipePageDelta(travel, threshold, multiTouch, zoom, enabled, rightAdvances) else 0
}

/** Claim horizontal drags before the text list scrolls, leaving taps and vertical drags to it. */
internal fun Modifier.readerPageSwipes(pageKey: Any, enabled: Boolean, rightAdvances: Boolean,
    manualScroll: () -> Unit, turn: (Int) -> Unit): Modifier = pointerInput(pageKey, enabled, rightAdvances) {
    if (!enabled) return@pointerInput
    val threshold = maxOf(64.dp.toPx(), minOf(size.width * .18f, 120.dp.toPx()))
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val swipe = PageSwipeGesture(viewConfiguration.touchSlop)
        var claimed = false
        var cancelled = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            cancelled = event.changes.any { it.isConsumed }
            if (!cancelled) {
                swipe.move(event.calculatePan(), event.changes.count { it.pressed || it.previousPressed })
                if (swipe.horizontal) {
                    if (!claimed) { claimed = true; manualScroll() }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                val final = awaitPointerEvent(PointerEventPass.Final)
                if (!claimed && final.changes.any { it.isConsumed }) cancelled = true
            }
        } while (!cancelled && event.changes.any { it.pressed })
        if (!cancelled) {
            val delta = swipe.pageDelta(threshold, enabled, rightAdvances)
            if (delta != 0) turn(delta)
        }
    }
}
