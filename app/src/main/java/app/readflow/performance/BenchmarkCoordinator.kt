package app.readflow.performance

import app.readflow.benchmark.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns one benchmark across configuration changes and holds exclusivity until native work drains. */
class BenchmarkCoordinator(
    private val scope: CoroutineScope,
    private val store: BenchmarkStore,
    private val prepare: suspend () -> Unit,
    private val execute: suspend (BenchmarkOptions, (BenchmarkState) -> Unit) -> BenchmarkState,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutable = MutableStateFlow(BenchmarkState())
    val state = mutable.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private var job: Job? = null
    private var revision = 0L
    @Volatile private var cancelling = false
    @Volatile private var cancellationReason: String? = null

    init {
        val expectedRevision = revision
        scope.launch {
            val restored = withContext(ioDispatcher) { store.load() }
            if (revision == expectedRevision && !mutableBusy.value && restored != null) mutable.value = restored
        }
    }

    /** Called on the app's main dispatcher. Returns false while another run is still draining. */
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    fun start(options: BenchmarkOptions): Boolean {
        if (mutableBusy.value) return false
        val validated = options.validated()
        revision++; cancelling = false; cancellationReason = null; mutableBusy.value = true
        mutable.value = BenchmarkState(status = BenchmarkStatus.RUNNING,
            phase = "Pausing reading and preparing the test", options = validated)
        val assigned = CompletableDeferred<Unit>()
        // ATOMIC enters try/finally even when cancel() is called before initial dispatch.
        job = scope.launch(start = CoroutineStart.ATOMIC) {
            var latest = mutable.value
            try {
                // Cancelling while waiting for old OCR/TTS must not release the gate early.
                withContext(NonCancellable) {
                    // Main.immediate may enter this coroutine before launch() returns. Publish
                    // its Job before prepare() can synchronously call back into cancel().
                    assigned.await()
                    prepare()
                }
                currentCoroutineContext().ensureActive()
                latest = execute(validated) { next ->
                    latest = next
                    mutable.value = next.copy(status = BenchmarkStatus.RUNNING,
                        phase = if (cancelling) "Stopping · waiting for model work to finish" else next.phase)
                }
            } catch (cancel: CancellationException) {
                latest = latest.copy(status = BenchmarkStatus.CANCELLED, phase = "Test cancelled")
            } catch (error: Exception) {
                latest = latest.copy(status = BenchmarkStatus.FAILED, phase = "Test could not finish",
                    error = error.message?.take(512) ?: "Benchmark unavailable")
            } finally {
                withContext(NonCancellable) {
                    if (latest.running) latest = latest.copy(status = BenchmarkStatus.FAILED, phase = "Test interrupted")
                    if (latest.status == BenchmarkStatus.CANCELLED) latest = latest.copy(cancellationReason = cancellationReason)
                    try { withContext(ioDispatcher) { store.save(latest) } }
                    catch (_: Exception) {
                        latest = latest.copy(error = listOfNotNull(latest.error, "Results could not be saved; copy them before closing.").joinToString(" "))
                    }
                    mutable.value = latest
                    mutableBusy.value = false
                }
            }
        }
        assigned.complete(Unit)
        return true
    }

    fun cancel(reason: String = "Stopped by user") {
        if (!mutableBusy.value) return
        if (!cancelling) cancellationReason = reason.take(160)
        cancelling = true
        mutable.value = mutable.value.copy(status = BenchmarkStatus.RUNNING,
            phase = "Stopping · waiting for model work to finish")
        job?.cancel()
    }

    fun report(): String = store.report(mutable.value)
}
