package works.merc.keryx.app.presentation.home

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import works.merc.keryx.app.domain.LocalWriteFailureReporter
import works.merc.keryx.app.domain.PendingLocalWrites

/**
 * Runs DB writes one at a time, each **to completion** — suspensions included — before the next
 * starts, in the order they were [enqueue]d.
 *
 * A single-threaded dispatcher alone does not give that: it only serializes the *slices* between
 * suspension points, so a write that suspends (`FeedRepository.moveFeed` waiting on the feed
 * placement lock) lets a write enqueued after it run, and land, first. Here one consumer coroutine
 * takes the next write only once the previous one has finished.
 *
 * Each write runs as its own child of [scope], on [dispatcher], through
 * [LocalWriteFailureReporter.runLocalWrite]: a write that fails is reported (logged, and posted to the
 * notification center) rather than thrown, so it neither ends the app nor holds up the writes after
 * it, and the caller's `onFailure` undoes what the UI showed ahead of it.
 *
 * Every write is counted in [pendingLocalWrites] from [enqueue] until it has finished — run, failed,
 * or dropped unrun because [scope] ended — so a foreground-exit sync flush waits for the sync it
 * schedules (see [PendingLocalWrites]). `null` counts nothing.
 */
internal class SerialWriteQueue(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val localWriteFailureReporter: LocalWriteFailureReporter,
    private val pendingLocalWrites: PendingLocalWrites? = null,
) {
    private class Entry(val write: suspend () -> Unit, val afterWrite: (() -> Unit)?, val onFailure: (() -> Unit)?)

    // An entry the consumer never gets to — still buffered when the queue is cancelled below, or
    // taken by an iteration that was cancelled before handing it over — is uncounted here.
    private val entries = Channel<Entry>(Channel.UNLIMITED, onUndeliveredElement = { pendingLocalWrites?.end() })

    init {
        scope.launch(dispatcher) {
            for (entry in entries) {
                try {
                    var landed = false
                    val job = scope.launch(dispatcher) { landed = localWriteFailureReporter.runLocalWrite(entry.write) }
                    job.join()
                    // A cancelled write (its scope ended) runs neither follow-up; join() orders the
                    // write's own assignment of `landed` before this read.
                    if (!job.isCancelled) {
                        val followUp = if (landed) entry.afterWrite else entry.onFailure
                        if (followUp != null) scope.launch { followUp() }
                    }
                } finally {
                    pendingLocalWrites?.end()
                }
            }
        }.invokeOnCompletion {
            // The consumer has ended (the scope was cancelled, even before it started): drop what is
            // still queued, and refuse later enqueues, so none of it stays counted.
            entries.cancel()
        }
    }

    /** Queues [write] behind every write enqueued before it and returns at once. */
    fun enqueue(write: suspend () -> Unit) = enqueue(write, afterWrite = null, onFailure = null)

    /** As the three-argument `enqueue`, with no `onFailure` — trailing-lambda friendly for [afterWrite]. */
    fun enqueue(write: suspend () -> Unit, afterWrite: (() -> Unit)?) = enqueue(write, afterWrite, onFailure = null)

    /**
     * As `enqueue(write)`, then runs [afterWrite] once [write] has completed successfully.
     *
     * @param afterWrite Run on [scope]'s own dispatcher (the ViewModel's main thread for a
     *   `viewModelScope`) rather than [dispatcher] — for UI state that must be checked again after the
     *   write landed. The write having landed does not mean the DB-backed flows have delivered it yet:
     *   UI state that must wait for the change to show (a delete's selection fallback) is handed on
     *   from here to whatever watches those flows ([DeletedItemFallback]). Skipped when [write]
     *   fails; it does not hold back the next write. `null` = none.
     * @param onFailure Run, like [afterWrite], on [scope]'s own dispatcher once [write] has failed (and
     *   been reported) — to undo UI state the caller changed ahead of the write. `null` = none.
     */
    fun enqueue(write: suspend () -> Unit, afterWrite: (() -> Unit)?, onFailure: (() -> Unit)?) {
        pendingLocalWrites?.begin()
        // A refused entry (the queue was cancelled with its scope) never reaches onUndeliveredElement.
        if (entries.trySend(Entry(write, afterWrite, onFailure)).isFailure) pendingLocalWrites?.end()
    }
}
