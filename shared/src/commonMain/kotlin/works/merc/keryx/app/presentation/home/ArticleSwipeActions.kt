package works.merc.keryx.app.presentation.home

/**
 * What a swipe on an article row does. Every UI that offers the swipe (SwiftUI on iOS and macOS,
 * Compose on a touch-primary Android) asks [articleSwipeLeadingAction] / [articleSwipeTrailingAction]
 * which one to show and runs it through [HomeViewModel.performSwipeAction], so the edges, their
 * labels and their effect are the same on every platform — only the presentation is each OS's own
 * (buttons revealed by the swipe on Apple, act-on-release past a threshold on Android).
 *
 * Each action names the state it sets rather than toggling, like the row's context menu: the label a
 * user saw is what happens, even if the row's snapshot changes underneath the gesture.
 */
enum class ArticleSwipeAction {
    MarkRead,
    MarkUnread,
    Star,
    Unstar,
}

/**
 * The action on an article row's leading edge (a swipe from left to right in a left-to-right
 * layout): read/unread, the edge Mail and Gmail use for it. A swipe never selects the row, so —
 * unlike the context menu's read item ([articleReadAfterContextMenuOpen]) — the row's own read state
 * decides it.
 */
fun articleSwipeLeadingAction(isRead: Boolean): ArticleSwipeAction =
    if (isRead) ArticleSwipeAction.MarkUnread else ArticleSwipeAction.MarkRead

/**
 * The action on an article row's trailing edge (a swipe from right to left in a left-to-right
 * layout): star/unstar.
 */
fun articleSwipeTrailingAction(isStarred: Boolean): ArticleSwipeAction =
    if (isStarred) ArticleSwipeAction.Unstar else ArticleSwipeAction.Star
