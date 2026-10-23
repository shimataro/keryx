package works.merc.keryx.app.presentation.home

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import works.merc.keryx.app.data.local.db.Feeds

/**
 * How long a feed has to stay in refresh before its row shows the refreshing indicator. Most feeds
 * finish well under this, so only the slow ones are marked and a fast refresh never flickers.
 */
const val REFRESH_INDICATOR_DELAY_MS = 400L

/**
 * The members of the upstream set that have stayed in it continuously for at least [delayMillis].
 * A member leaving the upstream set leaves the result at once; re-entering restarts its wait.
 * Emits the empty set first.
 */
internal fun Flow<Set<String>>.membersHeldFor(delayMillis: Long): Flow<Set<String>> = channelFlow {
    // Guards timers/shown: the timer coroutines and the upstream collector run concurrently.
    val lock = Mutex()
    val timers = HashMap<String, Job>()
    var shown = emptySet<String>()
    send(shown)
    collect { current ->
        lock.withLock {
            (timers.keys - current).forEach { timers.remove(it)?.cancel() }
            val kept = shown intersect current
            if (kept != shown) {
                shown = kept
                send(shown)
            }
            for (id in current - timers.keys) {
                timers[id] = launch {
                    delay(delayMillis)
                    lock.withLock {
                        // A cancel issued while this was waiting for the lock is honored here.
                        if (isActive) {
                            shown = shown + id
                            send(shown)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The folders whose row shows the refreshing indicator: those that are collapsed and hold a feed in
 * [refreshingFeedIds]. An expanded folder shows nothing, since its feeds' own rows already do.
 */
fun collapsedFoldersRefreshing(
    feeds: List<Feeds>,
    refreshingFeedIds: Set<String>,
    collapsedFolderIds: Set<String>,
): Set<String> {
    if (refreshingFeedIds.isEmpty() || collapsedFolderIds.isEmpty()) return emptySet()
    return feeds.asSequence()
        .filter { it.id in refreshingFeedIds }
        .mapNotNull { it.folder_id }
        .filter { it in collapsedFolderIds }
        .toSet()
}
