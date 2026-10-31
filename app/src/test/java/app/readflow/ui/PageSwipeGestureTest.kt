package app.readflow.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSwipeGestureTest {
    private fun PageSwipeGesture.delta(enabled: Boolean = true, right: Boolean = true, zoom: Float = 1f) =
        pageDelta(64f, enabled, right, zoom)

    @Test fun rightAdvancesAndLeftGoesBackByExactlyOnePage() {
        val right = PageSwipeGesture(8f).apply { move(Offset(20f, 2f), 1); move(Offset(200f, 3f), 1) }
        val left = PageSwipeGesture(8f).apply { move(Offset(-20f, 2f), 1); move(Offset(-200f, 3f), 1) }
        assertEquals(1, right.delta())
        assertEquals(-1, left.delta())
        assertEquals(-1, right.delta(right = false))
        assertEquals(1, left.delta(right = false))
    }

    @Test fun verticalScrollCannotChangePagesWhenFingerLaterMovesSideways() {
        val swipe = PageSwipeGesture(8f)
        swipe.move(Offset(2f, 20f), 1)
        swipe.move(Offset(200f, 0f), 1)
        assertFalse(swipe.horizontal)
        assertEquals(0, swipe.delta())
    }

    @Test fun tapJitterAndShortDragDoNotChangePage() {
        val tap = PageSwipeGesture(8f).apply { move(Offset(3f, 2f), 1); move(Offset(1f, 1f), 1) }
        assertFalse(tap.horizontal)
        assertEquals(0, tap.delta())
        val drag = PageSwipeGesture(8f).apply { move(Offset(50f, 0f), 1) }
        assertTrue(drag.horizontal)
        assertEquals(0, drag.delta())
    }

    @Test fun pinchingCannotBecomePageSwipeAfterSecondFingerLifts() {
        val swipe = PageSwipeGesture(8f)
        swipe.move(Offset(20f, 0f), 1)
        swipe.move(Offset(30f, 0f), 2)
        swipe.move(Offset(200f, 0f), 1)
        assertFalse(swipe.horizontal)
        assertEquals(0, swipe.delta())
    }

    @Test fun zoomedPanningAndDisabledSwipesDoNotTurnPages() {
        val swipe = PageSwipeGesture(8f).apply { move(Offset(200f, 0f), 1) }
        assertEquals(0, swipe.delta(zoom = 2f))
        assertEquals(0, swipe.delta(enabled = false))
    }

    @Test fun reversedDragMustFinishPastThresholdToTurnPage() {
        val swipe = PageSwipeGesture(8f)
        swipe.move(Offset(100f, 0f), 1)
        swipe.move(Offset(-90f, 0f), 1)
        assertEquals(0, swipe.delta())
        swipe.move(Offset(-100f, 0f), 1)
        assertEquals(-1, swipe.delta())
    }
}
