package works.merc.keryx.app.presentation.home

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.runCatchingUnlessCancelled

private const val TAG = "DeletedItemFallback"

/** A feed-list item whose delete this ViewModel has written: an unsubscribed feed, a tag or a folder. */
internal sealed interface DeletedTarget {
    val id: String

    data class Feed(override val id: String) : DeletedTarget

    data class Tag(override val id: String) : DeletedTarget

    data class Folder(override val id: String) : DeletedTarget
}

/**
 * Runs a delete's selection fallback once [shown] — the list the feed list renders the item from —
 * stops showing the deleted item.
 *
 * A delete's write lands before the list flow delivers it, so for a moment the feed list still shows
 * the item and the user can select it again; falling back when the write lands would leave such a
 * selection on a deleted item. Instead [fallBackOnceHidden] falls back at once if [shown] already
 * lacks the item, and otherwise keeps the request pending until a newer value of [shown] lacks it,
 * then falls back and drops the request.
 *
 * A value that still has the item — the one at hand when the request is made, or a newer one — is
 * either stale — a list query that read the database before
 * the delete committed (re-run for an earlier write to the same table, say) and was delivered late —
 * or shows the item re-created in between (a tag or folder of the same name, a feed subscribed again,
 * by this ViewModel or by a sync). Emission order cannot tell the two apart, so the committed state
 * decides: [isLive] is asked off the main thread, on [checkDispatcher], whether the item is live. That
 * read starts after the delete committed, so it sees at least that state. If the item is live it was
 * re-created, and the request is dropped without falling back; if not, the value was stale and the
 * request stays pending. Only the check started for the newest such value is applied, and a check
 * whose request was decided meanwhile (a value lacking the item arrived) is ignored.
 *
 * The check is best effort (docs/error-design.md): a read that fails — waiting out the whole
 * `busy_timeout` behind a sync merge's or refresh's write lock, say, which is when stale values tend
 * to arrive — is logged and treated as "not live", so the request stays pending and the next value of
 * [shown] decides it. Propagated, the failure would end the app on Android and Apple.
 *
 * Whether [shown] has the item is decided in the id space of [currentRemap], read afresh at each
 * decision: the request's id and every shown id are both mapped through the current re-key map
 * (`TagFolderIdRemaps`) before they are compared. A merge that re-keys the deleted item's row
 * publishes the map before it notifies the list queries, so a list value that shows the row under its
 * new id is never taken for "the item is gone" merely because this class has not yet been told to move
 * the request ([remapPending] is delivered on another coroutine, in no fixed order against [shown]).
 *
 * **An absence is confirmed against the committed re-key map before it is believed.** A merge
 * publishes the map only after its transaction commits, and any other write in between (a feed
 * refresh, a user action) re-runs the tag/folder list queries, which then already read the re-keyed
 * rows while [currentRemap] still holds the old map: the item looks gone when it only moved. So when
 * [shown] lacks the item and [catchUp] is given, it is run first (on [checkDispatcher]; in production
 * it re-reads `ks_id_remap` from the database, serialized by `TagFolderIdRemaps`' lock — the list was
 * read after the commit, so the read sees the re-key), and the decision is taken again with the
 * refreshed map: still absent means really gone and the fallback runs, present means the request
 * goes on as for a value that still shows the item. The decision is taken on the very list that was
 * caught up for, never on a newer value of [shown]: the map the catch-up read is at least as new as
 * that list, but a list delivered during the catch-up can show a later re-key the map does not have
 * yet. When [shown] has moved on by then, that newer value is decided afresh — with a catch-up of its
 * own — instead. After one catch-up an unexplained absence is a
 * real disappearance, so a real deletion always falls back. A [catchUp] that fails is logged and
 * leaves the request pending (best effort, as the liveness check): the next value of [shown] decides.
 * Without [catchUp] (feeds, whose ids are never re-keyed) an absence is decided at once.
 *
 * Once dropped, a request is gone, so a deletion that arrives later from elsewhere (a sync merge)
 * never moves the selection (external-spec §9, "Least surprise").
 *
 * Main thread only: [fallBackOnceHidden] is called, and [shown] collected and the checks' results
 * applied, on [scope]'s dispatcher (the ViewModel's `viewModelScope`), so the pending requests need
 * no lock.
 */
internal class DeletedItemFallback<T>(
    private val scope: CoroutineScope,
    private val shown: StateFlow<List<T>>,
    private val checkDispatcher: CoroutineDispatcher,
    private val isLive: suspend (id: String) -> Boolean,
    private val currentRemap: () -> (String) -> String = { { it } },
    private val catchUp: (suspend () -> Unit)? = null,
    private val idOf: (T) -> String,
) {
    /**
     * [reference] is the [shown] value the request last looked at — the one at the request, then the
     * latest value that still had the item — so a newer one is told apart by identity.
     * [checkGeneration] numbers the liveness checks, so only the newest one's result is applied.
     */
    private class Pending(var id: String, var reference: List<*>, val fallback: () -> Unit) {
        var checkGeneration = 0
    }

    private val pending = ArrayList<Pending>()

    init {
        scope.launch { shown.collect(::onShown) }
    }

    /**
     * Runs [fallback] once [shown] no longer has the item [id] — see the class KDoc.
     *
     * When [shown] still has the item, its liveness is checked at once as well as on each newer value:
     * called once the delete has landed, the value at hand can already show the item re-created (a
     * write queued right behind the delete — an OPML import, a sync merge — can land, and reach the
     * list, before this runs). No newer value would then arrive to prompt a check, and the request
     * would stay pending until some later deletion completed it.
     */
    fun fallBackOnceHidden(id: String, fallback: () -> Unit) {
        val current = shown.value
        val request = Pending(id, current, fallback)
        if (!shows(current, id)) {
            if (catchUp == null) {
                fallback()
                return
            }
            pending += request
            decideAbsence(request, current)
            return
        }
        pending += request
        checkLiveness(request)
    }

    /**
     * Moves every pending request onto the id [remap] gives it — a merge re-keyed the deleted item
     * onto another id (see `TagFolderIdRemapping`), whose row now stands for it. A moved request
     * is decided against the current value of [shown] at once, as a new request would be: if the list
     * lacks the new id, the absence is confirmed as any other is (`decideAbsence`) — with [catchUp], the
     * committed map is read first, since the list can already show a later re-key of the same row
     * (A -> B delivered here while the list shows C, the database holding B -> C) — and otherwise that
     * row's liveness is checked. A list that
     * still shows an old id — the request's previous one, or an earlier id of a chain of re-keys that
     * [remap], which is folded, sends to the same new id — was read before the re-key, so it cannot
     * tell whether the new id is shown: the request then stays pending, with that row's liveness
     * checked, as for a list that shows the new id. Requests whose id does not move are untouched.
     */
    fun remapPending(remap: (String) -> String) {
        if (pending.isEmpty()) return
        val current = shown.value
        for (request in pending.toList()) {
            val previous = request.id
            val moved = remap(previous)
            if (moved == previous) continue
            request.id = moved
            // A list that still shows an id [remap] sends to the new one (the previous id, or one from
            // an earlier re-key of the same chain, Z -> M -> A with the list still at Z) predates the
            // re-key, so its lacking the new id proves nothing: the request stays pending, decided by
            // the liveness check or a newer value.
            val predatesTheRemap = current.any { idOf(it).let { shown -> shown != moved && remap(shown) == moved } }
            if (!predatesTheRemap && current.none { idOf(it) == moved }) {
                request.reference = current
                decideAbsence(request, current)
            } else {
                request.reference = current
                checkLiveness(request)
            }
        }
    }

    /** Whether [list] has the item [id], both compared after the current re-key map. */
    private fun shows(list: List<T>, id: String): Boolean {
        val remap = currentRemap()
        val target = remap(id)
        return list.any { remap(idOf(it)) == target }
    }

    private fun onShown(list: List<T>) {
        if (pending.isEmpty()) return
        // A StateFlow collector can still be handed the value a request last saw; only a newer one counts.
        for (request in pending.filter { it.reference !== list }) decide(request, list)
    }

    /** Decides [request] on [list], a value of [shown] it has not been decided on yet. */
    private fun decide(request: Pending, list: List<T>) {
        request.reference = list
        if (!shows(list, request.id)) decideAbsence(request, list) else checkLiveness(request)
    }

    /**
     * [list], a value of [shown], lacks the item of [request]: falls back at once without a [catchUp],
     * else confirms the absence against the committed re-key map first (see the class KDoc). Shares
     * the request's check generation with [checkLiveness], so only the newest decision for the request
     * is applied.
     *
     * After the catch-up the decision is taken on [list] itself: the map just read is at least as new
     * as [list] (which was read before the catch-up started), but not necessarily as new as a value of
     * [shown] delivered during it, which can show a later re-key under an id the map does not know yet.
     * If [shown] has moved past [list] meanwhile, [list] is superseded and the newest value is decided
     * here and now, with a catch-up of its own — not left to [onShown], whose collector skips a value
     * equal to the one it last delivered, so a list that changed and changed back would never reach it.
     * When [onShown] has already decided the request on that value, the generation check below stops
     * this continuation first, and when it decides it later it skips the value as already seen.
     */
    private fun decideAbsence(request: Pending, list: List<T>) {
        val catchUp = catchUp
        if (catchUp == null) {
            pending.remove(request)
            request.fallback()
            return
        }
        val generation = ++request.checkGeneration
        scope.launch {
            val caughtUp = runCatchingUnlessCancelled {
                withContext(checkDispatcher) { catchUp() }
            }.onFailure { e ->
                Log.warn(TAG, "Catching up the tag/folder re-keys before a deleted item's fallback failed", e)
            }.isSuccess
            if (request !in pending || generation != request.checkGeneration) return@launch
            // Undecided: the next value of [shown] asks again.
            if (!caughtUp) return@launch
            val latest = shown.value
            if (latest !== list) {
                decide(request, latest)
                return@launch
            }
            if (shows(list, request.id)) {
                request.reference = list
                checkLiveness(request)
            } else {
                pending.remove(request)
                request.fallback()
            }
        }
    }

    private fun checkLiveness(request: Pending) {
        val generation = ++request.checkGeneration
        scope.launch {
            val live = runCatchingUnlessCancelled {
                withContext(checkDispatcher) { isLive(request.id) }
            }.getOrElse { e ->
                Log.warn(TAG, "Checking whether a deleted feed-list item is live failed", e)
                false
            }
            // Ignore a result whose request was decided meanwhile, or that a newer check supersedes.
            if (request !in pending || generation != request.checkGeneration) return@launch
            if (live) pending.remove(request)
        }
    }
}
