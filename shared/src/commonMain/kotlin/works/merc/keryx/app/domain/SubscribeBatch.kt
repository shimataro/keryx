package works.merc.keryx.app.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Every committed write is numbered, and a prefetch notes how many had committed when it read the row
 * ([FeedRepository.PrefetchedSubscription.observedBatchWrites]). A record is the baseline only for a
 * prefetch that read the row **before** that write; one that read it after already saw the batch's
 * write, and anything that changed the row since is someone else's.
 *
 * For that to hold, the count a prefetch notes and the row it reads must be of the same moment. A
 * batch write ([write]: its transaction, then its [record]) and a prefetch's read ([observe]: the
 * count, then the row) therefore exclude each other under one lock. Without it a batch write — and a
 * refresh after it — could commit between the two reads: the prefetch would read the refresh's values
 * with a count from before the batch write, and take that older write as its baseline, dropping its
 * own newer fetch.
 *
 * The count is only read or written under [lock], which orders it across the prefetches' coroutines;
 * the records are written under it too, and read by the batch's one writing coroutine.
 */
internal class SubscribeBatch {
    /** A value the batch wrote, with the number of the write that wrote it. */
    class Recorded<T>(val value: T, val writeNumber: Int)

    /** What a prefetch read with [observe]: [value], and how many batch writes had committed then. */
    class Observed<T>(val writes: Int, val value: T)

    private val lock = Mutex()
    private var committed = 0
    private val contents = HashMap<String, Recorded<FeedRepository.FeedValues>>()
    private val favicons = HashMap<String, Recorded<String?>>()

    /**
     * Runs [read] — a prefetch's read of the row — together with reading how many batch writes have
     * committed, with no batch write between the two (see the class KDoc). Hold it only for the read:
     * every batch write waits on it.
     */
    suspend fun <T> observe(read: () -> T): Observed<T> = lock.withLock { Observed(committed, read()) }

    /**
     * Runs [block] — one batch write, from its transaction through its [record] — with no prefetch
     * read in between (see the class KDoc).
     */
    suspend fun <T> write(block: suspend () -> T): T = lock.withLock { block() }

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
     * blanked) when [replacedContent], [favicon] when [replacedFavicon]. Called within [write].
     */
    fun record(
        feedId: String,
        content: FeedRepository.FeedValues,
        replacedContent: Boolean,
        favicon: String?,
        replacedFavicon: Boolean,
    ) {
        val number = committed + 1
        if (replacedContent) contents[feedId] = Recorded(content, number)
        if (replacedFavicon) favicons[feedId] = Recorded(favicon, number)
        committed = number
    }
}
