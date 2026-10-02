package works.merc.keryx.app.presentation

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** One edge of a [ManualSync.syncNow] run — see [ManualSync.runs]. */
enum class ManualSyncEdge { Started, Finished }

/**
 * The single "Sync now" action every route shares — Home's toolbar button, the Feed menu item
 * (desktop menu bar and SwiftUI `Commands`), and the cloud-sync settings tab's button — so they
 * run the same sync under the same guard and are enabled and disabled together (see "Actions with
 * more than one route" in `docs/external-spec.md` §9).
 * [works.merc.keryx.app.presentation.settings.CloudSyncController] is the one implementation; Home
 * reaches it through this interface rather than the whole controller.
 */
interface ManualSync {
    /**
     * Whether a manual sync may start right now. False while no provider is connected, while any
     * refresh or sync is running, while a connect / disconnect / reset / connect-time initial sync
     * is in flight, and while the last sync failed on authorization (a sync then would only repeat
     * that failure; reconnecting is what fixes it).
     */
    val canSyncNow: StateFlow<Boolean>

    /** Starts a manual sync; a no-op whenever [canSyncNow] is false. */
    fun syncNow()

    /**
     * [ManualSyncEdge.Started] just before every sync [syncNow] starts and
     * [ManualSyncEdge.Finished] once it ends (success, failure or cancellation alike), whichever
     * route started it — so a screen that has to react to a manual sync (Home re-trims its pinned
     * read rows) does so even when the sync was started from another screen. Not replayed: a
     * collector only sees edges emitted while it is subscribed.
     */
    val runs: SharedFlow<ManualSyncEdge>
}
