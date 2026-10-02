package works.merc.keryx.app.presentation.home

/**
 * Explicit read-state intents (a "mark as read/unread" on one article, or a mark-all-read) made
 * while at least one [HomeViewModel.selectArticle] hydration is still loading its body, keyed by
 * article id and tagged with a sequence number.
 *
 * A hydration resumes after its DB lookup, which can wait out a whole busy_timeout under a sync
 * merge's write lock; by then the user may already have said "mark as unread" on the very article
 * being selected (a right-click on an unselected row selects it through its own `onOpen` before
 * the menu's action runs). A hydration captures [begin]'s sequence number when the selection is
 * made and asks [since] on completion, so only an intent made *after* that selection overrides the
 * selection's implicit "read".
 *
 * Bounded: [record] only stores anything while a hydration is in flight, and the map is cleared
 * when the last in-flight hydration calls [end]. Every [begin] must be paired with exactly one
 * [end] (call [end] from a `finally`).
 *
 * **Main thread only.** Not synchronized: [HomeViewModel] touches it only from the UI's main thread
 * — its public methods are called there, and a hydration's continuation resumes on its Main
 * dispatcher.
 */
internal class SelectionReadIntents {
    private val intents = HashMap<String, Pair<Long, Boolean>>()
    private var seq = 0L
    private var inFlight = 0

    /**
     * Marks a hydration as started.
     *
     * @return The current sequence number, to pass to [since] once the hydration completes.
     */
    fun begin(): Long {
        inFlight++
        return seq
    }

    /** Marks a hydration as finished; once none is left in flight, every recorded intent is dropped. */
    fun end() {
        check(inFlight > 0) { "end() without a matching begin()" }
        if (--inFlight == 0) intents.clear()
    }

    /**
     * Records an explicit read-state intent for [id], superseding any earlier one for it. A no-op
     * while no hydration is in flight, since nothing could then override it.
     *
     * @param id The article the intent applies to.
     * @param read Whether the article should end up read.
     */
    fun record(id: String, read: Boolean) {
        if (inFlight > 0) intents[id] = (++seq) to read
    }

    /**
     * The latest intent for [id] made after [seq] was captured by [begin].
     *
     * @param id The article to look up.
     * @param seq The sequence number [begin] returned for the hydration asking.
     * @return The intended read state, or `null` when no intent was recorded for [id] since then.
     */
    fun since(id: String, seq: Long): Boolean? = intents[id]?.takeIf { it.first > seq }?.second
}
