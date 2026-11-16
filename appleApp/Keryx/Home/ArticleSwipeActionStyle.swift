import KeryxShared
import SwiftUI

/// How an article row's swipe action looks — its label, symbol and color. Which action an edge shows
/// and what it does are decided once in `:shared` (`ArticleSwipeActions.kt`, run through
/// `HomeViewModel.performSwipeAction`); this only presents it, for both the iOS `List`'s
/// `.swipeActions` and the macOS table's row actions.
enum ArticleSwipeActionStyle {
    // Looked up once; the app's language cannot change while it runs. The context menu's own labels.
    private static let markReadLabel = L("article_mark_as_read")
    private static let markUnreadLabel = L("article_mark_as_unread")
    private static let starLabel = L("article_star")
    private static let unstarLabel = L("article_unstar")

    /// The action on the row's leading edge — read/unread.
    static func leading(_ row: ArticleRowModel) -> ArticleSwipeAction {
        ArticleSwipeActionsKt.articleSwipeLeadingAction(isRead: row.isRead)
    }

    /// The action on the row's trailing edge — star/unstar.
    static func trailing(_ row: ArticleRowModel) -> ArticleSwipeAction {
        ArticleSwipeActionsKt.articleSwipeTrailingAction(isStarred: row.isStarred)
    }

    static func title(_ action: ArticleSwipeAction) -> String {
        switch action {
        case .markRead: markReadLabel
        case .markUnread: markUnreadLabel
        case .star: starLabel
        case .unstar: unstarLabel
        }
    }

    /// Read/unread shows the state the action leaves the article in, as the reader toolbar's dot does.
    /// Star does too (`star.fill`); unstar is `star.slash`, the way Mail's swipe shows unflag
    /// (`flag.slash`) — an outline star alone on the colored button would not read as "remove".
    static func systemImage(_ action: ArticleSwipeAction) -> String {
        switch action {
        case .markRead: "circle"
        case .markUnread: "circle.fill"
        case .star: "star.fill"
        case .unstar: "star.slash"
        }
    }

    /// Read/unread in the accent color of the row's unread dot; star in orange, as Mail's flag
    /// button is. The button's icon is always white, which the row's own star yellow would leave at
    /// about 1.5:1; system orange reaches about 2.2:1, and with Increase Contrast its high-contrast
    /// variant about 5.3:1.
    static func tint(_ action: ArticleSwipeAction) -> Color {
        isStar(action) ? .orange : .accentColor
    }

    #if os(macOS)
    /// `tint` as AppKit's row action takes it.
    static func nsColor(_ action: ArticleSwipeAction) -> NSColor {
        isStar(action) ? .systemOrange : .controlAccentColor
    }
    #endif

    /// Which of the two color groups an action belongs to — the one place `tint` and `nsColor` decide it.
    private static func isStar(_ action: ArticleSwipeAction) -> Bool {
        switch action {
        case .markRead, .markUnread: false
        case .star, .unstar: true
        }
    }
}
