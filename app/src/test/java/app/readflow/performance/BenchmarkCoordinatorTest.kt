package app.readflow.performance

import app.readflow.benchmark.BenchmarkOptions
import app.readflow.benchmark.BenchmarkRow
import app.readflow.benchmark.BenchmarkState
import app.readflow.benchmark.BenchmarkStatus
import app.readflow.benchmark.BenchmarkStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class BenchmarkCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()

    private val options = BenchmarkOptions(ocrEngines = emptyList(), repetitions = 1)
    private val measuredRow = BenchmarkRow(kind = "speech", model = "kokoro", sample = "warm 1",
        totalMs = 500, characters = 30, audioSeconds = 2.0)

    @Test fun reservesBeforeDispatchAndRejectsAnotherStartUntilPreparationCompletes() = runTest {
        val store = BenchmarkStore(temporary.newFolder())
        val releasePreparation = CompletableDeferred<Unit>()
        var prepareCalls = 0
        var executeCalls = 0
        val coordinator = BenchmarkCoordinator(this, store,
            prepare = { prepareCalls++; releasePreparation.await() },
            execute = { chosen, _ ->
                executeCalls++
                BenchmarkState(status = BenchmarkStatus.COMPLETED, options = chosen, rows = listOf(measuredRow))
            }, ioDispatcher = StandardTestDispatcher(testScheduler))

        assertTrue(coordinator.start(options))
        assertTrue(coordinator.busy.value)
        assertEquals(BenchmarkStatus.RUNNING, coordinator.state.value.status)
        assertEquals(0, prepareCalls)
        assertFalse(coordinator.start(options))
        runCurrent()
        assertEquals(1, prepareCalls)
        assertEquals(0, executeCalls)
        assertFalse(coordinator.start(options))

        releasePreparation.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, executeCalls)
        assertFalse(coordinator.busy.value)
        assertEquals(BenchmarkStatus.COMPLETED, coordinator.state.value.status)
        assertEquals(listOf(measuredRow), store.load()!!.rows)
    }

    @Test fun cancellationBeforeInitialDispatchStillDrainsOldWorkAndReleasesReservation() = runTest {
        val store = BenchmarkStore(temporary.newFolder())
        val preparationEntered = CompletableDeferred<Unit>()
        val releasePreparation = CompletableDeferred<Unit>()
        var executeCalls = 0
        val coordinator = BenchmarkCoordinator(this, store,
            prepare = { preparationEntered.complete(Unit); releasePreparation.await() },
            execute = { _, _ -> executeCalls++; BenchmarkState(status = BenchmarkStatus.COMPLETED) },
            ioDispatcher = StandardTestDispatcher(testScheduler))

        assertTrue(coordinator.start(options))
        coordinator.cancel("ReadFlow moved to the background")
        assertTrue(coordinator.busy.value)
        runCurrent()
        assertTrue(preparationEntered.isCompleted)
        assertEquals(BenchmarkStatus.RUNNING, coordinator.state.value.status)
        assertFalse(coordinator.start(options))

        releasePreparation.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, executeCalls)
        assertFalse(coordinator.busy.value)
        assertEquals(BenchmarkStatus.CANCELLED, coordinator.state.value.status)
        assertEquals(BenchmarkStatus.CANCELLED, store.load()!!.status)
        assertEquals("ReadFlow moved to the background", store.load()!!.cancellationReason)
    }

    @Test fun cancellationDuringOldWorkDrainDoesNotReleaseExclusivityEarly() = runTest {
        val releasePreparation = CompletableDeferred<Unit>()
        var executeCalls = 0
        val coordinator = BenchmarkCoordinator(this, BenchmarkStore(temporary.newFolder()),
            prepare = { releasePreparation.await() },
            execute = { _, _ -> executeCalls++; BenchmarkState(status = BenchmarkStatus.COMPLETED) },
            ioDispatcher = StandardTestDispatcher(testScheduler))

        coordinator.start(options)
        runCurrent()
        coordinator.cancel()
        runCurrent()
        assertTrue(coordinator.busy.value)
        assertTrue(coordinator.state.value.phase.contains("waiting"))
        assertFalse(coordinator.start(options))

        releasePreparation.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, executeCalls)
        assertFalse(coordinator.busy.value)
        assertEquals(BenchmarkStatus.CANCELLED, coordinator.state.value.status)
    }

    @Test fun synchronousPrepareCallbackCanCancelItsOwnAssignedJob() = runTest {
        var executeCalls = 0
        lateinit var coordinator: BenchmarkCoordinator
        coordinator = BenchmarkCoordinator(
            CoroutineScope(coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            BenchmarkStore(temporary.newFolder()),
            prepare = { coordinator.cancel() },
            execute = { _, _ -> executeCalls++; BenchmarkState(status = BenchmarkStatus.COMPLETED) },
            ioDispatcher = StandardTestDispatcher(testScheduler))

        assertTrue(coordinator.start(options))
        advanceUntilIdle()
        assertEquals(0, executeCalls)
        assertFalse(coordinator.busy.value)
        assertEquals(BenchmarkStatus.CANCELLED, coordinator.state.value.status)
    }

    @Test fun cancelledRunnerKeepsCompletedRowsAndBusyGateUntilNativeCleanupFinishes() = runTest {
        val store = BenchmarkStore(temporary.newFolder())
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val coordinator = BenchmarkCoordinator(this, store, prepare = {}, execute = { chosen, publish ->
            publish(BenchmarkState(status = BenchmarkStatus.RUNNING, options = chosen,
                completed = 1, total = 3, rows = listOf(measuredRow)))
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupEntered.complete(Unit)
                    releaseCleanup.await()
                }
            }
        }, ioDispatcher = StandardTestDispatcher(testScheduler))

        coordinator.start(options)
        runCurrent()
        assertEquals(listOf(measuredRow), coordinator.state.value.rows)
        coordinator.cancel()
        runCurrent()
        assertTrue(cleanupEntered.isCompleted)
        assertTrue(coordinator.busy.value)
        assertFalse(coordinator.start(options))

        releaseCleanup.complete(Unit)
        advanceUntilIdle()
        assertFalse(coordinator.busy.value)
        assertEquals(BenchmarkStatus.CANCELLED, coordinator.state.value.status)
        assertEquals(1, coordinator.state.value.completed)
        assertEquals(listOf(measuredRow), coordinator.state.value.rows)
        assertEquals(listOf(measuredRow), store.load()!!.rows)
    }

    @Test fun runnerFailurePreservesPublishedMeasurementsAndCanStartAgain() = runTest {
        val store = BenchmarkStore(temporary.newFolder())
        var attempts = 0
        val coordinator = BenchmarkCoordinator(this, store, prepare = {}, execute = { chosen, publish ->
            attempts++
            val measured = BenchmarkState(status = BenchmarkStatus.RUNNING, options = chosen,
                completed = 1, total = 2, rows = listOf(measuredRow))
            publish(measured)
            if (attempts == 1) throw IOException("Synthetic model failure")
            measured.copy(status = BenchmarkStatus.COMPLETED)
        }, ioDispatcher = StandardTestDispatcher(testScheduler))

        coordinator.start(options)
        advanceUntilIdle()
        assertFalse(coordinator.busy.value)
        assertEquals(BenchmarkStatus.FAILED, coordinator.state.value.status)
        assertEquals("Synthetic model failure", coordinator.state.value.error)
        assertEquals(listOf(measuredRow), store.load()!!.rows)
        assertTrue(coordinator.start(options))
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertEquals(BenchmarkStatus.COMPLETED, coordinator.state.value.status)
        assertNull(coordinator.state.value.error)
    }

    @Test fun interruptedStoredRunCannotOverwriteANewRunWhileRestorationIsPending() = runTest {
        val store = BenchmarkStore(temporary.newFolder())
        val oldRow = measuredRow.copy(sample = "previous interrupted run")
        store.save(BenchmarkState(status = BenchmarkStatus.RUNNING, rows = listOf(oldRow)))
        val releaseRunner = CompletableDeferred<Unit>()
        val coordinator = BenchmarkCoordinator(this, store, prepare = {}, execute = { chosen, publish ->
            val measured = BenchmarkState(status = BenchmarkStatus.RUNNING, options = chosen,
                rows = listOf(measuredRow))
            publish(measured)
            releaseRunner.await()
            measured.copy(status = BenchmarkStatus.COMPLETED)
        }, ioDispatcher = StandardTestDispatcher(testScheduler))
        val observed = mutableListOf<BenchmarkState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.state.collect { observed += it }
        }

        // Init dispatches its old report read, then this starts the new run before init resumes.
        launch { assertTrue(coordinator.start(options)) }
        runCurrent()
        assertTrue(coordinator.busy.value)
        assertEquals(BenchmarkStatus.RUNNING, coordinator.state.value.status)
        assertEquals(listOf(measuredRow), coordinator.state.value.rows)
        assertFalse(observed.any { oldRow in it.rows })

        releaseRunner.complete(Unit)
        advanceUntilIdle()
        assertEquals(BenchmarkStatus.COMPLETED, coordinator.state.value.status)
        assertEquals(listOf(measuredRow), store.load()!!.rows)
        collector.cancel()
    }

    @Test fun invalidOptionsDoNotReserveTheDeviceOrSchedulePreparation() = runTest {
        var prepareCalls = 0
        var executeCalls = 0
        val coordinator = BenchmarkCoordinator(this, BenchmarkStore(temporary.newFolder()),
            prepare = { prepareCalls++ },
            execute = { _, _ -> executeCalls++; BenchmarkState(status = BenchmarkStatus.COMPLETED) },
            ioDispatcher = StandardTestDispatcher(testScheduler))

        for (invalid in listOf(options.copy(repetitions = 2),
                options.copy(speechModels = emptyList(), ocrEngines = emptyList()))) {
            try {
                coordinator.start(invalid)
                fail("Invalid options should be rejected")
            } catch (_: IllegalArgumentException) { }
            assertFalse(coordinator.busy.value)
            assertEquals(BenchmarkStatus.IDLE, coordinator.state.value.status)
        }
        advanceUntilIdle()
        assertEquals(0, prepareCalls)
        assertEquals(0, executeCalls)
        assertTrue(coordinator.start(options))
        advanceUntilIdle()
        assertEquals(1, prepareCalls)
        assertEquals(1, executeCalls)
    }
}
