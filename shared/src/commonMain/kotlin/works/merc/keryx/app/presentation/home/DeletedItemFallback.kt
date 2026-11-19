package works.merc.keryx.app.presentation.home

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * A newer value that still has the item is either stale — a list query that read the database before
 * the delete committed (re-run for an earlier write to the same table, say) and was delivered late —
 * or shows the item re-created in between (a tag or folder of the same name, a feed subscribed again,
 * by this ViewModel or by a sync). Emission order cannot tell the two apart, so the committed state
 * decides: [isLive] is asked off the main thread, on [checkDispatcher], whether the item is live. That
 * read starts after the delete committed, so it sees at least that state. If the item is live it was
 * re-created, and the request is dropped without falling back; if not, the value was stale and the
 * request stays pending. Only the check started for the newest such value is applied, and a check
 * whose request was decided meanwhile (a value lacking the item arrived) is ignored.
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
    private val idOf: (T) -> String,
) {
    /**
     * [reference] is the [shown] value the request last looked at — the one at the request, then the
     * latest value that still had the item — so a newer one is told apart by identity.
     * [checkGeneration] numbers the liveness checks, so only the newest one's result is applied.
     */
    private class Pending(val id: String, var reference: List<*>, val fallback: () -> Unit) {
        var checkGeneration = 0
    }

    private val pending = ArrayList<Pending>()

    init {
        scope.launch { shown.collect(::onShown) }
    }

    /** Runs [fallback] once [shown] no longer has the item [id] — see the class KDoc. */
    fun fallBackOnceHidden(id: String, fallback: () -> Unit) {
        val current = shown.value
        if (current.none { idOf(it) == id }) fallback() else pending += Pending(id, current, fallback)
    }

    private fun onShown(list: List<T>) {
        if (pending.isEmpty()) return
        // A StateFlow collector can still be handed the value a request last saw; only a newer one counts.
        for (request in pending.filter { it.reference !== list }) {
            if (list.none { idOf(it) == request.id }) {
                pending.remove(request)
                request.fallback()
            } else {
                request.reference = list
                checkLiveness(request)
            }
        }
    }

    private fun checkLiveness(request: Pending) {
        val generation = ++request.checkGeneration
        scope.launch {
            val live = withContext(checkDispatcher) { isLive(request.id) }
            // Ignore a result whose request was decided meanwhile, or that a newer check supersedes.
            if (request !in pending || generation != request.checkGeneration) return@launch
            if (live) pending.remove(request)
        }
    }
}
