package works.merc.keryx.app.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Counts the local DB writes that have been requested but have not run yet — the ones a caller
 * queues off its own thread and returns from at once (a global-setting change in
 * `SettingsRepository`, the feed list's writes in `SerialWriteQueue`, `HomeViewModel`'s read/star
 * writes).
 *
 * Such a write schedules its sync only once it runs, so until then nothing tells
 * [SyncRepository.flushPendingSync] that a change is on its way: a flush made as the app leaves the
 * foreground right after the change would find nothing pending and return without it. The flush
 * therefore waits here first ([awaitIdle]) — every write counted by then has scheduled its sync.
 *
 * Every [begin] must be matched by exactly one [end], whether the write succeeds, fails, or is
 * cancelled before it starts; a missed [end] would hold every later flush up to its bound.
 *
 * A `MutableStateFlow` rather than an atomic counter, since commonMain has no stable atomic type
 * (as `SyncRepository`'s own flags).
 */
class PendingLocalWrites {
    private val count = MutableStateFlow(0)

    /** Marks a write as requested. Call it before the write is queued, on the requesting thread. */
    fun begin() {
        count.update { it + 1 }
    }

    /** Marks a write [begin] counted as finished — run, failed, or dropped. */
    fun end() {
        count.update { it - 1 }
    }

    /** Suspends until every write counted so far has finished. Returns at once when there is none. */
    suspend fun awaitIdle() {
        count.first { it <= 0 }
    }
}
