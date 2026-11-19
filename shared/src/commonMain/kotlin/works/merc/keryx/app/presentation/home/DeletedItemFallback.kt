package works.merc.keryx.app.presentation.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
 * lacks the item, and otherwise on the first new value [shown] delivers after the call — only if that
 * value lacks the item, since one that still has it means the item was re-created (a tag or folder of
 * the same name, a feed subscribed again) in between. Either way the request is then dropped, so a
 * deletion that arrives later from elsewhere (a sync merge) never moves the selection
 * (external-spec §9, "Least surprise").
 *
 * Main thread only: [fallBackOnceHidden] is called, and [shown] collected, on [scope]'s dispatcher
 * (the ViewModel's `viewModelScope`), so the pending requests need no lock.
 */
internal class DeletedItemFallback<T>(
    scope: CoroutineScope,
    private val shown: StateFlow<List<T>>,
    private val idOf: (T) -> String,
) {
    /** [shownWhenRequested] is the exact [shown] value at the request, so a newer one is told apart by identity. */
    private class Pending(val id: String, val shownWhenRequested: List<*>, val fallback: () -> Unit)

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
        // A StateFlow collector can still be handed the value a request saw; only a newer one decides it.
        val decided = pending.filter { it.shownWhenRequested !== list }
        if (decided.isEmpty()) return
        pending.removeAll { it.shownWhenRequested !== list }
        for (request in decided) {
            if (list.none { idOf(it) == request.id }) request.fallback()
        }
    }
}
