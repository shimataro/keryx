package works.merc.keryx.app.core

/** Which set of articles the home screen's article list is currently showing. */
sealed interface ArticleFilter {
    data object All : ArticleFilter
    data object Starred : ArticleFilter

    /**
     * Full-text search results for the current query (held in `HomeViewModel._searchQuery`, not in
     * this variant — so a keystroke doesn't churn the filter). A persisted "search" filter is
     * downgraded to [All] on restart since the query text isn't persisted.
     */
    data object Search : ArticleFilter
    data class Feed(val feedId: String) : ArticleFilter
    data class Tag(val tagId: String) : ArticleFilter
    data class Folder(val folderId: String) : ArticleFilter
}

fun ArticleFilter.encode(): String = when (this) {
    ArticleFilter.All -> "all"
    ArticleFilter.Starred -> "starred"
    ArticleFilter.Search -> "search"
    is ArticleFilter.Feed -> "feed:$feedId"
    is ArticleFilter.Tag -> "tag:$tagId"
    is ArticleFilter.Folder -> "folder:$folderId"
}

fun decodeArticleFilter(encoded: String): ArticleFilter? = when {
    encoded == "all" -> ArticleFilter.All
    // Decode-only compatibility for a value [encode] no longer produces, so an older-app-version
    // persisted filter still round-trips instead of resolving to null (see decodeArticleFilter's
    // else branch below).
    encoded == "unread" -> ArticleFilter.All
    encoded == "starred" -> ArticleFilter.Starred
    encoded == "search" -> ArticleFilter.Search
    encoded.startsWith("feed:") -> ArticleFilter.Feed(encoded.removePrefix("feed:"))
    encoded.startsWith("tag:") -> ArticleFilter.Tag(encoded.removePrefix("tag:"))
    encoded.startsWith("folder:") -> ArticleFilter.Folder(encoded.removePrefix("folder:"))
    else -> null
}
