package works.merc.keryx.app.domain

import works.merc.keryx.app.core.FEED_ERROR_REASON_GONE

/**
 * Why the feed list flags a feed, from its device-local fetch state (`feed_fetch_state`). A feed
 * with no problem has no entry at all.
 */
enum class FeedFetchProblem {
    /** The last fetch on this device failed (`error_count > 0`). */
    ERROR,

    /**
     * The feed answered 410 Gone. A 410 deliberately leaves `error_count` alone (it is permanent,
     * not a retry candidate), so the `last_error` marker is what identifies it.
     */
    GONE,
}

/**
 * The problem a feed's fetch state shows, or `null` for none. The Gone marker wins over a non-zero
 * error count, so a feed that is gone always says so.
 *
 * @param errorCount The feed's `error_count` (0 for a feed with no fetch state).
 * @param lastError The feed's `last_error`.
 */
fun feedFetchProblem(errorCount: Long, lastError: String?): FeedFetchProblem? = when {
    lastError == FEED_ERROR_REASON_GONE -> FeedFetchProblem.GONE
    errorCount > 0 -> FeedFetchProblem.ERROR
    else -> null
}
