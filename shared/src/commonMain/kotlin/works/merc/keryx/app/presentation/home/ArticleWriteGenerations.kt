package works.merc.keryx.app.presentation.home

/**
 * The latest read (or, in a second instance, star) write the user made on each article — what
 * [HomeViewModel] asks when one of those writes fails, to undo its optimistic state only on the
 * articles the user has not acted on again since: on any other, a newer action owns what is shown
 * and decides it once its own write lands (or is undone in turn, should it fail too).
 *
 * Every write gets a fresh generation ([stamp]); an entry lives until its write has finished
 * ([release]), so the map only ever holds articles with a write in flight or a revert pending.
 *
 * **Main thread only**, like the optimistic state it guards.
 */
internal class ArticleWriteGenerations {
    private var last = 0L
    private val latest = HashMap<String, Long>()

    /** Records a new write on [ids] and returns its generation. */
    fun stamp(ids: Collection<String>): Long {
        val generation = ++last
        ids.forEach { latest[it] = generation }
        return generation
    }

    /** Whether the write of [generation] is still the latest one made on [id]. */
    fun isLatest(id: String, generation: Long): Boolean = latest[id] == generation

    /**
     * Moves the latest write of each article a feed merge moved onto another id ([moved]: old id -> id
     * it has now) onto that id; of two meeting on one id, the newer generation stands.
     */
    fun remap(moved: Map<String, String>) {
        for ((old, new) in moved) {
            val generation = latest.remove(old) ?: continue
            if ((latest[new] ?: 0L) < generation) latest[new] = generation
        }
    }

    /** Forgets the write of [generation] on [ids], except where a newer write has replaced it. */
    fun release(ids: Collection<String>, generation: Long) {
        ids.forEach { if (latest[it] == generation) latest.remove(it) }
    }
}
