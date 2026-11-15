package app.readflow.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal data class ManualHistoryEvent(val documentId: String, val page: Int, val time: Long, val opened: Boolean)

/** Ordered writes plus a barrier ensure a quick return reads the page just left. */
internal class ManualHistoryWriter(
    scope: CoroutineScope,
    private val persist: suspend (ManualHistoryEvent) -> Unit,
    private val onFailure: suspend (ManualHistoryEvent, Exception) -> Unit,
) {
    private sealed interface Message {
        data class Write(val value: ManualHistoryEvent) : Message
        data class Barrier(val complete: CompletableDeferred<Unit>) : Message
    }
    private val messages = Channel<Message>(Channel.UNLIMITED)
    private val worker = scope.launch {
        for (message in messages) when (message) {
            is Message.Barrier -> message.complete.complete(Unit)
            is Message.Write -> try { persist(message.value) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    // Failure reporting must not strand later writes or barrier waiters.
                    try { onFailure(message.value, error) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { }
                }
        }
    }.also { job -> job.invokeOnCompletion { cause -> messages.close(cause) } }

    fun record(event: ManualHistoryEvent) { messages.trySend(Message.Write(event)) }

    suspend fun flush() {
        val complete = CompletableDeferred<Unit>()
        val stopped = worker.invokeOnCompletion { cause ->
            complete.completeExceptionally(cause ?: CancellationException("Reading-history writer stopped"))
        }
        try {
            messages.send(Message.Barrier(complete))
            complete.await()
        } finally { stopped.dispose() }
    }
}
