package works.merc.keryx.app.domain

import kotlinx.coroutines.flow.MutableStateFlow

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
 * Every committed write is numbered ([committedWrites] counts them), and a prefetch notes the count
 * before it reads the row ([FeedRepository.PrefetchedSubscription.observedBatchWrites]). A record is
 * the baseline only for a prefetch that read the row **before** that write; one that read it after
 * already saw the batch's write, and anything that changed the row since is someone else's.
 *
 * The records are written and read only by the batch's one writing coroutine, between its serial
 * writes; the count is also read by the prefetches, on other coroutines, so it is a `MutableStateFlow`
 * (commonMain has no stable atomic type, as with [PendingLocalWrites]).
 */
internal class SubscribeBatch {
    /** A value the batch wrote, with the number of the write that wrote it. */
    class Recorded<T>(val value: T, val writeNumber: Int)

    private val committed = MutableStateFlow(0)
    private val contents = HashMap<String, Recorded<FeedRepository.FeedValues>>()
    private val favicons = HashMap<String, Recorded<String?>>()

    /** How many of the batch's writes have committed so far. */
    fun committedWrites(): Int = committed.value

    /**
     * The content (favicon blanked) the batch last wrote to [feedId], if it did so after a prefetch
     * that had seen [observedWrites] committed writes.
     */
    fun contentWrittenAfter(feedId: String, observedWrites: Int): FeedRepository.FeedValues? =
        contents[feedId]?.takeIf { it.writeNumber > observedWrites }?.value

    /** As [contentWrittenAfter], for the favicon; `null` when there is no such write. */
    fun faviconWrittenAfter(feedId: String, observedWrites: Int): Recorded<String?>? =
        favicons[feedId]?.takeIf { it.writeNumber > observedWrites }

    /**
     * Counts one committed write to [feedId] and records the groups it replaced: [content] (favicon
     * blanked) when [replacedContent], [favicon] when [replacedFavicon].
     */
    fun record(
        feedId: String,
        content: FeedRepository.FeedValues,
        replacedContent: Boolean,
        favicon: String?,
        replacedFavicon: Boolean,
    ) {
        val number = committed.value + 1
        if (replacedContent) contents[feedId] = Recorded(content, number)
        if (replacedFavicon) favicons[feedId] = Recorded(favicon, number)
        // Counted after the records, so a prefetch that sees the new count also finds them.
        committed.value = number
    }
}
