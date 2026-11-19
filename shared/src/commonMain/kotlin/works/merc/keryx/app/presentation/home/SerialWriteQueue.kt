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
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(dispatcher) {
            for (write in writes) {
                // join() does not rethrow the write's failure; the failure is the child's own.
                scope.launch(dispatcher) { write() }.join()
            }
        }
    }

    /** Queues [write] behind every write enqueued before it and returns at once. */
    fun enqueue(write: suspend () -> Unit) {
        writes.trySend(write)
    }
}
