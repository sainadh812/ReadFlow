package app.readflow.ui

import org.junit.Assert.*
import org.junit.Test

class ThumbnailPolicyTest {
    @Test fun panoramicAndVeryTallSourcesStayWithinThePixelBudget() {
        listOf(1200 to 1800, 9000 to 80, 80 to 9000, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE,
            Int.MAX_VALUE to Int.MAX_VALUE).forEach { (width, height) ->
            val size = thumbnailSize(width, height)
            assertTrue(size.width in 1..384)
            assertTrue(size.height in 1..544)
            assertTrue(size.width.toLong() * size.height * 4 <= 384L * 544 * 4)
        }
        assertEquals(ThumbnailSize(384, 96), thumbnailSize(1200, 300))
    }

    @Test fun smallImagesAreNotUpscaledAndInvalidHeadersAreRejected() {
        assertEquals(ThumbnailSize(2, 3), thumbnailSize(2, 3))
        assertThrows(IllegalArgumentException::class.java) { thumbnailSize(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { thumbnailSize(100, -1) }
    }

    @Test fun leastRecentlyUsedPreviewsLeaveTheCacheWithoutInvalidatingVisibleOwners() {
        val cache = ThumbnailMemoryCache<String, Any>(8, 8)
        val visible = Any()
        cache.put("first", visible, 4)
        cache.put("second", Any(), 4)
        assertSame(visible, cache.get("first")) // first is now most recently used
        cache.put("third", Any(), 4)
        assertNull(cache.get("second"))
        assertSame(visible, cache.get("first"))
        assertEquals(8L, cache.byteCount)
        cache.put("fourth", Any(), 8)
        assertNull(cache.get("first"))
        assertNotNull(visible) // ownership is a reference, not a recycling callback
        assertEquals(1, cache.entryCount)
    }

    @Test fun failuresAndReplacementEntriesCannotGrowTheCacheWithoutBound() {
        val cache = ThumbnailMemoryCache<Int, String>(100, 3)
        repeat(1000) { cache.put(it, "failed", 0) }
        assertEquals(3, cache.entryCount)
        assertEquals(3L, cache.byteCount)
        cache.put(999, "replacement", 50)
        assertEquals(52L, cache.byteCount)
        cache.put(1001, "oversized", 101)
        assertNull(cache.get(1001))
        assertEquals(52L, cache.byteCount)
    }
}
