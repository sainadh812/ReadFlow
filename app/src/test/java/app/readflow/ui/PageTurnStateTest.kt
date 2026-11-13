package app.readflow.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PageTurnStateTest {
    private fun TestScope.turnState(turns: MutableList<Int>, animate: Boolean = true): PageTurnState =
        PageTurnState(this, animationsEnabled = { animate }, motion = { _, target, duration, frame ->
            delay(duration.toLong()); frame(target)
        }).apply {
            update("book" to 1, 1, 4, "curl", true, false, turns::add)
            resize(IntSize(400, 700))
        }

    @Test fun leftDragCommitsNextExactlyOnceEvenWithRepeatedRelease() = runTest {
        val turns = mutableListOf<Int>()
        val state = turnState(turns)
        state.drag(Offset(-150f, 0f))
        state.release(1)
        state.release(1)
        runCurrent()
        state.release(1)
        state.requestTurn(1)
        assertEquals(listOf(1), turns)
        assertTrue(state.committed)
        state.update("book" to 2, 2, 4, "curl", true, false, turns::add)
        state.contentReady(true)
        runCurrent()
        assertFalse(state.active)
    }

    @Test fun cancellingShortDragDoesNotNavigateOrPrepareAPage() = runTest {
        val turns = mutableListOf<Int>()
        val state = turnState(turns)
        state.drag(Offset(-22f, 0f))
        state.release(0)
        advanceTimeBy(200); runCurrent()
        assertTrue(turns.isEmpty())
        assertFalse(state.active)
        assertFalse(state.committed)
    }

    @Test fun documentChangeCancelsPendingCaptureAndNavigation() = runTest {
        val turns = mutableListOf<Int>()
        val state = turnState(turns)
        state.capture = { delay(200); null }
        state.drag(Offset(-150f, 0f))
        state.release(1)
        runCurrent()
        state.update("different-book" to 2, 2, 4, "curl", true, false, turns::add)
        advanceTimeBy(500); runCurrent()
        assertTrue(turns.isEmpty())
        assertFalse(state.active)
    }

    @Test fun failedSnapshotStillNavigatesAndSlowTargetCannotTrapGestures() = runTest {
        val turns = mutableListOf<Int>()
        val state = turnState(turns)
        state.capture = { error("Render surface unavailable") }
        state.drag(Offset(-150f, 0f))
        state.release(1)
        runCurrent()
        assertEquals(listOf(1), turns)
        advanceTimeBy(8_001); runCurrent()
        assertFalse(state.active)
        assertFalse(state.committed)
    }

    @Test fun offAndReducedMotionDoNotCaptureOrWaitForAnAnimation() = runTest {
        val turns = mutableListOf<Int>()
        val state = turnState(turns, animate = false)
        var captures = 0
        state.capture = { captures++; null }
        state.drag(Offset(-150f, 0f))
        state.release(1)
        assertEquals(listOf(1), turns)
        assertEquals(0, captures)
        assertFalse(state.active)
        state.update("book" to 2, 2, 4, "off", true, false, turns::add)
        state.release(-1)
        assertEquals(listOf(1, -1), turns)
    }

    @Test fun waitingForTargetKeepsOutgoingPageUntilItsReadySignal() = runTest {
        val turns = mutableListOf<Int>()
        val state = turnState(turns)
        state.drag(Offset(-150f, 0f)); state.release(1); runCurrent()
        state.update("book" to 2, 2, 4, "curl", true, false, turns::add)
        state.contentReady(false)
        advanceTimeBy(400); runCurrent()
        assertTrue(state.active)
        assertFalse(state.incomingReady)
        state.contentReady(true); runCurrent()
        assertFalse(state.active)
    }

    @Test fun resizeAndDisabledControlsCancelAnUncommittedDrag() = runTest {
        val turns = mutableListOf<Int>()
        val state = turnState(turns)
        state.drag(Offset(-150f, 0f))
        state.resize(IntSize(700, 400))
        assertFalse(state.active)
        state.drag(Offset(-150f, 0f))
        state.update("book" to 1, 1, 4, "curl", false, false, turns::add)
        state.release(1); runCurrent()
        assertFalse(state.active)
        assertTrue(turns.isEmpty())
    }
}
