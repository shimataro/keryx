package works.merc.keryx.app.presentation.home

/** The pane that should hold keyboard focus when the home screen first appears. */
enum class InitialHomePane { FeedList, ArticleList, ArticleDetail }

/**
 * Decides which pane gets keyboard focus at launch, so the window never opens with no pane focused.
 *
 * The previous session's pane ([savedPane], stored under the desktop `HomePane` names) is restored
 * only when the selection it was focused on survived: the article list and the reader need the
 * restored article ([articleRestored]) to have something selected to act on. Anything that cannot be
 * reproduced — a first launch, a filter whose feed/folder/tag was deleted ([filterRestored] false),
 * a deleted article, an unknown pane name — falls back to the feed list, which always has a selected
 * row (the restored filter, or All Feeds when that too was lost).
 */
fun resolveInitialHomePane(savedPane: String?, filterRestored: Boolean, articleRestored: Boolean): InitialHomePane {
    if (!filterRestored || !articleRestored) return InitialHomePane.FeedList
    return when (savedPane) {
        "ArticleList" -> InitialHomePane.ArticleList
        "ArticleDetail" -> InitialHomePane.ArticleDetail
        else -> InitialHomePane.FeedList
    }
}
