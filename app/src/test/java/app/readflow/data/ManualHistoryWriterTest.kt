package app.readflow.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualHistoryWriterTest {
    @Test fun quickReturnWaitsForPendingPageWritesBeforeReadingTheSavedPage() = runTest {
        val release = CompletableDeferred<Unit>()
        val persisted = mutableListOf<ManualHistoryEvent>()
        val writer = ManualHistoryWriter(backgroundScope, { event -> release.await(); persisted += event }, { _, error -> throw error })
        writer.record(ManualHistoryEvent("book", 4, 1, true))
        writer.record(ManualHistoryEvent("book", 7, 2, false))
        val restored = async { writer.flush(); persisted.last().page }
        runCurrent()
        assertFalse(restored.isCompleted)
        release.complete(Unit)
        assertEquals(7, restored.await())
        assertEquals(listOf(4, 7), persisted.map { it.page })
    }

    @Test fun failedWriteOrReporterCannotStrandSubsequentRestores() = runTest {
        val saved = mutableListOf<String>()
        var failures = 0
        val writer = ManualHistoryWriter(backgroundScope, { event ->
            if (event.documentId == "failed") error("Storage failure")
            saved += event.documentId
        }, { _, _ -> failures++; error("Reporter failure") })
        writer.record(ManualHistoryEvent("failed", 1, 1, true))
        writer.record(ManualHistoryEvent("working", 2, 2, false))
        writer.flush()
        assertEquals(1, failures)
        assertEquals(listOf("working"), saved)
    }
}
