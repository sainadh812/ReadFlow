package app.readflow.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class PageViewportTest {
    @Test fun fitWidthLocksHorizontalMovementAndClampsVerticalScroll() {
        val view = PageViewport(600f, 1000f, 300f, 400f, true)
        assertEquals(Offset.Zero, view.constrain(Offset(200f, 200f), 1f))
        assertEquals(Offset(0f, -100f), view.constrain(Offset(-200f, -1000f), 1f))
    }
    @Test fun shortPagesStayCenteredWithNoFreePanning() {
        val view = PageViewport(600f, 400f, 300f, 400f, true)
        assertEquals(Offset(0f, 100f), view.base)
        assertEquals(Offset.Zero, view.constrain(Offset(999f, -999f), 1f))
        val whole = view.copy(pageHeight = 1000f, fitWidth = false)
        assertEquals(Offset(30f, 0f), whole.base)
        assertEquals(Offset.Zero, whole.constrain(Offset(-999f, 999f), 1f))
    }
    @Test fun zoomStaysAnchoredAndCannotDragBeyondAnyPageEdge() {
        val view = PageViewport(600f, 800f, 300f, 400f, true)
        val pan = view.zoomPan(Offset.Zero, 1f, 2f, Offset(150f, 200f), Offset.Zero)
        assertEquals(Offset(-150f, -200f), pan)
        assertEquals(Offset(-300f, -400f), view.constrain(Offset(-999f, -999f), 2f))
        assertEquals(Offset.Zero, view.constrain(Offset(999f, 999f), 2f))
        assertEquals(Offset.Zero, view.zoomPan(pan, 2f, 1f, Offset(150f, 200f), Offset.Zero))
    }
    @Test fun resizeOrRotationReclampsPanIntoTheNewViewport() {
        val rotated = PageViewport(800f, 600f, 400f, 300f, true)
        assertEquals(Offset.Zero, rotated.constrain(Offset(-150f, -200f), 1f))
        assertEquals(Offset(-400f, -300f), rotated.constrain(Offset(-999f, -999f), 2f))
    }
    @Test fun onlyDeliberateSingleFingerHorizontalSwipesTurnPages() {
        fun swipe(x: Float, y: Float = 0f, multi: Boolean = false, zoom: Float = 1f, enabled: Boolean = true, right: Boolean = true) =
            swipePageDelta(Offset(x, y), 64f, multi, zoom, enabled, right)
        assertEquals(1, swipe(100f)); assertEquals(-1, swipe(-100f))
        assertEquals(-1, swipe(100f, right = false)); assertEquals(1, swipe(-100f, right = false))
        assertEquals(0, swipe(63f)); assertEquals(0, swipe(100f, 100f))
        assertEquals(0, swipe(100f, multi = true)); assertEquals(0, swipe(100f, zoom = 2f))
        assertEquals(0, swipe(100f, enabled = false)); assertEquals(1, swipe(5000f))
    }
}
