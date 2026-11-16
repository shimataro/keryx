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

    /// The reader toolbar's own symbols for the state the action leaves the article in.
    static func systemImage(_ action: ArticleSwipeAction) -> String {
        switch action {
        case .markRead: "circle"
        case .markUnread: "circle.fill"
        case .star: "star.fill"
        case .unstar: "star.slash"
        }
    }

    /// Read/unread in the accent color of the row's unread dot; star in the row's own star yellow.
    static func tint(_ action: ArticleSwipeAction) -> Color {
        switch action {
        case .markRead, .markUnread: .accentColor
        case .star, .unstar: .yellow
        }
    }

    #if os(macOS)
    /// `tint` as AppKit's row action takes it.
    static func nsColor(_ action: ArticleSwipeAction) -> NSColor {
        switch action {
        case .markRead, .markUnread: .controlAccentColor
        case .star, .unstar: .systemYellow
        }
    }
    #endif
}
