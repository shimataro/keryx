package works.merc.keryx.app.domain

/**
 * What one batch of subscribe writes — an [OpmlImporter] run, which fetches entries ahead of writing
 * them — has itself written to each feed row, so a later entry that resolves to the same row compares
 * against the batch's own write rather than against a prefetch read taken before it (see
 * [FeedRepository.writePrefetchedSubscription]).
 *
 * The two groups a subscribe judges on their own are recorded on their own: the content with the
 * validators describing it, and the favicon. A group is recorded only when the batch **replaced** it
 * with fetched values — never when it kept a value another writer (a refresh) stored meanwhile, so a
 * later entry still sees that value as someone else's change and keeps it.
 *
 * Written and read only by the batch's one writing coroutine, between its serial writes.
 */
internal class SubscribeBatch {
    /** A value the batch wrote, wrapped so a recorded `null` favicon is told apart from "none recorded". */
    class Recorded<T>(val value: T)

    private val contents = HashMap<String, Recorded<FeedRepository.FeedValues>>()
    private val favicons = HashMap<String, Recorded<String?>>()

    /** The content (favicon blanked) the batch last wrote to [feedId], if it replaced it at all. */
    fun content(feedId: String): Recorded<FeedRepository.FeedValues>? = contents[feedId]

    /** The favicon the batch last wrote to [feedId], if it replaced it at all. */
    fun favicon(feedId: String): Recorded<String?>? = favicons[feedId]

    /**
     * Records one committed write to [feedId]: [content] (favicon blanked) when the write replaced the
     * content group, [favicon] when it replaced the favicon; `null` leaves that group's record as it was.
     */
    fun record(feedId: String, content: Recorded<FeedRepository.FeedValues>?, favicon: Recorded<String?>?) {
        if (content != null) contents[feedId] = content
        if (favicon != null) favicons[feedId] = favicon
    }
}
