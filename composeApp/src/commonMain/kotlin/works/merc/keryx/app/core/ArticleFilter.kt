package works.merc.keryx.app.core

/**
 * Which set of articles the home screen's article list is currently showing.
 *
 * Search is *not* one of these — it's an orthogonal, query-driven narrowing of whichever filter is
 * currently selected (see `HomeViewModel.searchActive`/`searchResults`), not a filter of its own.
 * A filter here is always the thing search is scoped to, never displaced by it.
 */
sealed interface ArticleFilter {
    data object All : ArticleFilter
    data object Starred : ArticleFilter
    data class Feed(val feedId: String) : ArticleFilter
    data class Tag(val tagId: String) : ArticleFilter
    data class Folder(val folderId: String) : ArticleFilter
}

fun ArticleFilter.encode(): String = when (this) {
    ArticleFilter.All -> "all"
    ArticleFilter.Starred -> "starred"
    is ArticleFilter.Feed -> "feed:$feedId"
    is ArticleFilter.Tag -> "tag:$tagId"
    is ArticleFilter.Folder -> "folder:$folderId"
}

/**
 * Decodes a serialized article filter.
 *
 * The legacy `unread` value is mapped to `ArticleFilter.All` for compatibility.
 *
 * @param encoded The serialized filter value.
 * @return The decoded article filter, or `null` for an unknown encoding.
 */
fun decodeArticleFilter(encoded: String): ArticleFilter? = when {
    encoded == "all" -> ArticleFilter.All
    // Decode-only compatibility for values [encode] no longer produces, so an older-app-version
    // persisted filter still round-trips instead of resolving to null (see decodeArticleFilter's
    // else branch below). "unread" was a since-removed filter folded into the unreadOnly toggle
    // instead; "search" was Search's own encoding from when it was still a filter that could
    // displace the one being browsed (see this file's own module KDoc) — restoring either as a
    // filter selection wouldn't mean anything any more, so both fall back to All.
    encoded == "unread" -> ArticleFilter.All
    encoded == "search" -> ArticleFilter.All
    encoded == "starred" -> ArticleFilter.Starred
    encoded.startsWith("feed:") -> ArticleFilter.Feed(encoded.removePrefix("feed:"))
    encoded.startsWith("tag:") -> ArticleFilter.Tag(encoded.removePrefix("tag:"))
    encoded.startsWith("folder:") -> ArticleFilter.Folder(encoded.removePrefix("folder:"))
    else -> null
}
