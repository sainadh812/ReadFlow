package app.readflow.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PageTurnGeometryTest {
    private fun area(points: List<Offset>): Float = abs(points.indices.sumOf { i ->
        val a = points[i]; val b = points[(i + 1) % points.size]
        (a.x * b.y - b.x * a.y).toDouble()
    }.toFloat()) / 2f

    @Test fun curlPreservesTheEntirePaperAreaAcrossBothDirections() {
        listOf(true, false).forEach { left ->
            listOf(.001f, .08f, .25f, .5f, .75f, .999f).forEach { progress ->
                val fold = pageFold(360f, 720f, progress, left)
                assertEquals(360f * 720f, area(fold.front) + area(fold.back), 2f)
            }
        }
    }

    @Test fun reflectedBackFaceMeetsTheStationaryPageAtTheCrease() {
        val fold = pageFold(400f, 700f, .3f, towardLeft = true)
        assertEquals(2, fold.crease.size)
        fold.crease.forEach { point ->
            assertEquals(point.x, fold.reflect(point).x, .001f)
            assertEquals(point.y, fold.reflect(point).y, .001f)
        }
        // Reflecting twice returns the original print position; text is mirrored only on the back.
        val point = Offset(370f, 620f)
        val twice = fold.reflect(fold.reflect(point))
        assertEquals(point.x, twice.x, .001f)
        assertEquals(point.y, twice.y, .001f)
    }

    @Test fun previousPageFoldMirrorsNextPageFold() {
        val next = pageFold(360f, 720f, .34f, towardLeft = true)
        val previous = pageFold(360f, 720f, .34f, towardLeft = false)
        assertEquals(area(next.front), area(previous.front), 1f)
        next.back.forEach { point ->
            assertTrue(previous.back.any { abs(it.x - (360f - point.x)) < .01f && abs(it.y - point.y) < .01f })
        }
    }

    @Test fun endpointsAndNarrowViewportsHaveFiniteFoldCoordinates() {
        listOf(0f, 1f, -.2f, 1.3f).forEach { progress ->
            val fold = pageFold(80f, 1000f, progress, towardLeft = true)
            (fold.front + fold.back + fold.crease).forEach { point ->
                assertTrue(point.x.isFinite()); assertTrue(point.y.isFinite())
            }
        }
    }

    @Test fun pagesCannotWrapSkipOrCommitACancelledGesture() {
        assertEquals(1, pageTurnTarget(0, 4, 1))
        assertEquals(1, pageTurnTarget(2, 4, -1))
        assertNull(pageTurnTarget(0, 4, -1))
        assertNull(pageTurnTarget(3, 4, 1))
        assertNull(pageTurnTarget(1, 4, 0))
        assertNull(pageTurnTarget(1, 4, 2))
    }
}
