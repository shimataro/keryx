package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.core.ArticleFilter

/**
 * Which articles of the selected [ArticleFilter] the article list shows — one of three, never a
 * combination.
 *
 * Exclusive on purpose: a separate "starred only" toggle beside "unread only" would let both be on at
 * once, and since an article is marked read the moment it is selected, almost every starred article is
 * read — so that combination would show an empty list that reads as "nothing is starred here".
 *
 * [All]/[Unread] are persisted (`lastUnreadOnly`, or `lastUnreadOnlyStarred` under
 * [ArticleFilter.Starred]); [Starred] lasts for the session only. It narrows a feed, folder, tag or
 * All Feeds, and is unavailable under [ArticleFilter.Starred], which already shows nothing else.
 */
enum class ArticleListMode { All, Unread, Starred }

/**
 * The display-side narrowing [HomeViewModel] applies on top of a filter's raw query result (and its
 * search results).
 */
internal data class ListNarrowing(val unreadOnly: Boolean, val starredOnly: Boolean)

/**
 * The mode the article list shows for [filter].
 *
 * @param filter The selected article filter.
 * @param unreadOnly The shared unread-only state every filter but [ArticleFilter.Starred] uses.
 * @param unreadOnlyStarred [ArticleFilter.Starred]'s own unread-only state.
 * @param starredMode Whether the session-only starred mode is on.
 */
internal fun articleListModeFor(
    filter: ArticleFilter,
    unreadOnly: Boolean,
    unreadOnlyStarred: Boolean,
    starredMode: Boolean,
): ArticleListMode = when {
    filter == ArticleFilter.Starred -> if (unreadOnlyStarred) ArticleListMode.Unread else ArticleListMode.All
    starredMode -> ArticleListMode.Starred
    unreadOnly -> ArticleListMode.Unread
    else -> ArticleListMode.All
}

/** Whether [ArticleListMode.Starred] can be chosen under [filter]. */
internal fun starredModeAvailable(filter: ArticleFilter): Boolean = filter != ArticleFilter.Starred
