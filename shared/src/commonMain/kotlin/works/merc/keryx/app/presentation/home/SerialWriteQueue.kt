package works.merc.keryx.app.presentation.home

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Runs DB writes one at a time, each **to completion** — suspensions included — before the next
 * starts, in the order they were [enqueue]d.
 *
 * A single-threaded dispatcher alone does not give that: it only serializes the *slices* between
 * suspension points, so a write that suspends (`FeedRepository.moveFeed` waiting on the feed
 * placement lock) lets a write enqueued after it run, and land, first. Here one consumer coroutine
 * takes the next write only once the previous one has finished.
 *
 * Each write runs as its own child of [scope], on [dispatcher], so a write that throws reaches the
 * scope's uncaught-exception handling exactly as a plain `scope.launch(dispatcher)` would — and,
 * with [scope]'s `SupervisorJob` (a `viewModelScope`), neither the consumer nor the writes after it
 * are cancelled by it.
 */
internal class SerialWriteQueue(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private class Entry(val write: suspend () -> Unit, val afterWrite: (() -> Unit)?)

    private val entries = Channel<Entry>(Channel.UNLIMITED)

    init {
        scope.launch(dispatcher) {
            for (entry in entries) {
                val job = scope.launch(dispatcher) { entry.write() }
                // join() does not rethrow the write's failure; the failure is the child's own.
                job.join()
                // A failed (or cancelled) job reports isCancelled; its afterWrite is skipped.
                if (entry.afterWrite != null && !job.isCancelled) scope.launch { entry.afterWrite.invoke() }
            }
        }
    }

    /** Queues [write] behind every write enqueued before it and returns at once. */
    fun enqueue(write: suspend () -> Unit) = enqueue(write, afterWrite = null)

    /**
     * As `enqueue(write)`, then runs [afterWrite] once [write] has completed successfully.
     *
     * @param afterWrite Run on [scope]'s own dispatcher (the ViewModel's main thread for a
     *   `viewModelScope`) rather than [dispatcher] — for UI state that must be checked again after the
     *   write landed. Skipped when [write] fails; it does not hold back the next write. `null` = none.
     */
    fun enqueue(write: suspend () -> Unit, afterWrite: (() -> Unit)?) {
        entries.trySend(Entry(write, afterWrite))
    }
}
