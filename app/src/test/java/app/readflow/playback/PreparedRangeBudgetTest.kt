package app.readflow.playback

import org.junit.Assert.*
import org.junit.Test

class PreparedRangeBudgetTest {
    @Test fun completedRangeAudioStaysProtectedAtTheLimit() {
        val budget = PreparedRangeBudget(100)
        budget.add("first", 40)
        budget.add("second", 60)
        assertEquals(100L, budget.bytes)
        assertEquals(setOf("first", "second"), budget.protectedKeys)
        assertFalse(budget.canAdd("third", 1))
        try { budget.add("third", 1); fail("Exceeded cache cap") }
        catch (_: IllegalStateException) { }
        assertEquals(setOf("first", "second"), budget.protectedKeys)
    }

    @Test fun revisitingAChunkDoesNotDoubleCountItsStorage() {
        val budget = PreparedRangeBudget(100)
        budget.add("first", 60)
        budget.add("first", 60)
        assertEquals(60L, budget.bytes)
        assertTrue(budget.canAdd("second", 40))
        assertFalse(budget.canAdd("second", 41))
    }

    @Test fun replacementCountsOnlyTheChangedBytes() {
        val budget = PreparedRangeBudget(100)
        budget.add("first", 40); budget.add("second", 40)
        assertTrue(budget.canAdd("first", 60))
        assertFalse(budget.canAdd("first", 61))
        budget.add("first", 20)
        assertEquals(60L, budget.bytes)
    }

    @Test fun comparisonsDoNotOverflowAndNegativeSizesAreRejected() {
        val budget = PreparedRangeBudget(Long.MAX_VALUE)
        budget.add("first", Long.MAX_VALUE - 10)
        assertTrue(budget.canAdd("second", 10))
        assertFalse(budget.canAdd("second", Long.MAX_VALUE))
        try { budget.canAdd("second", -1); fail("Accepted negative bytes") }
        catch (_: IllegalArgumentException) { }
    }
}
