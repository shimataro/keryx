package works.merc.keryx.app.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Session-only "background activity" tracker for the feed refresh (new-article check) and cloud
 * sync. Both the manual path ([HomeViewModel] via [FeedRepository.refreshAll]/[FeedRepository.refreshFeed]
 * and [SyncRepository.sync]) and the desktop background loop (`main.kt`, which has no ViewModel) run
 * their work inside [trackFeedRefresh] / [trackSync]; the UI observes [feedRefreshing] / [syncing] to
 * show a spinner. Sibling of [NotificationCenter]. Not persisted.
 *
 * A counter (rather than a plain boolean) keeps each indicator lit until *all* concurrent operations
 * finish — e.g. a background refresh overlapping a manual one, or a debounced sync overlapping a
 * manual sync. [MutableStateFlow.update] is CAS-atomic, so the counters never race.
 *
 * [scope] is injectable so tests can supply `runTest`'s `backgroundScope` (see [SyncRepository] for
 * the same pattern). The default is an app-lifetime scope, matching this class's Koin `single`.
 */
class ActivityCenter(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val feedRefreshCount = MutableStateFlow(0)

    val feedRefreshing: StateFlow<Boolean> =
        feedRefreshCount.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun <T> trackFeedRefresh(block: suspend () -> T): T {
        feedRefreshCount.update { it + 1 }
        try {
            return block()
        } finally {
            feedRefreshCount.update { it - 1 }
        }
    }

    private val syncCount = MutableStateFlow(0)

    val syncing: StateFlow<Boolean> =
        syncCount.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun <T> trackSync(block: suspend () -> T): T {
        syncCount.update { it + 1 }
        try {
            return block()
        } finally {
            syncCount.update { it - 1 }
        }
    }
}
