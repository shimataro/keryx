package works.merc.keryx.app.data.remote

/** A single article parsed from a feed, before it is persisted. */
data class ParsedArticle(
    val guid: String,
    val url: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val content: String? = null,
    val author: String? = null,
    val publishedAtMillis: Long? = null,
    val thumbnailUrl: String? = null,
)

/** Result of fetching + parsing a feed URL. */
data class FetchedFeed(
    val title: String? = null,
    val description: String? = null,
    val siteUrl: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    /** Set when the subscription URL should be permanently updated (301/308). */
    val redirectUrl: String? = null,
    val articles: List<ParsedArticle> = emptyList(),
    /**
     * True when the server answered 304 Not Modified, i.e. every other field is absent because
     * there was nothing to send — not because the feed stopped providing it. Callers must leave the
     * stored `etag` / `last_modified` alone in that case: overwriting them with these nulls drops
     * the validators, so the next request carries no `If-None-Match` / `If-Modified-Since` and the
     * server has to send the whole feed again.
     */
    val notModified: Boolean = false,
)
