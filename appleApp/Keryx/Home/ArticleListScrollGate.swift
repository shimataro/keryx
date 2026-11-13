/// Keeps the iOS article list's scrolls (`ArticleListView`) away from a list that is not on screen.
///
/// `ScrollViewProxy.scrollTo` does not scroll at once: SwiftUI carries it out in the list's next
/// layout. A list under the reader is not laid out, so a scroll asked for there (a swipe in the reader
/// moves the selection, a restored selection lands) would wait until the back navigation's first
/// layout, which is where v0.27.0 crashed in `UICollectionView`'s scroll target validation. So a
/// scroll asked for while the list is hidden is held, and only the latest one is carried out when the
/// list comes back, provided its row still exists. See "Article list scrolling (iOS)" in
/// `docs/app-architecture.md`.
struct ArticleListScrollGate {
    /// A scroll to carry out.
    struct Target: Equatable {
        var rowId: String
        /// Whether a row already on screen stays where it is (bringing the selection into view).
        var onlyIfOffScreen = false
    }

    /// What decides whether the list can be scrolled, as far as this decision needs it.
    struct Input: Equatable {
        /// Whether the list is on screen and past the rebuild that follows its window attachment, as
        /// a scroll carried out before that rebuild is lost (`ArticleListView.scrollGateInput`).
        var canScroll: Bool
        /// `ArticleListView.remountGeneration`.
        var remountGeneration: Int
    }

    private var canScroll = false
    /// nil until the first update.
    private var remountGeneration: Int?
    private var held: Target?

    /// A scroll was asked for.
    /// - Returns: The scroll to carry out now, or nil when it is held (or its row is gone).
    mutating func request(_ target: Target, rowExists: (String) -> Bool) -> Target? {
        guard canScroll else {
            held = target
            return nil
        }
        return rowExists(target.rowId) ? target : nil
    }

    /// Whether `ArticleListView` is showing its rows right now — the only time a scroll sent to
    /// it reaches the list. The rows and `listBody` arrive separately, so rows can
    /// be there while the body is still pending; a search shows the list only once its query has terms
    /// and results.
    static func listShowsRows(bodyIsContent: Bool, rowCount: Int, searchActive: Bool, searchShowsList: Bool) -> Bool {
        bodyIsContent && rowCount > 0 && (!searchActive || searchShowsList)
    }

    /// What the list can do changed.
    /// - Returns: The held scroll to ask for again through `request`, which checks its row, or nil to
    ///   leave the scroll position alone.
    mutating func update(_ input: Input) -> Target? {
        // A rebuilt list starts at the top by design, and scrolling it right after the rebuild makes
        // the row touched next jump (`ArticleListView.body`).
        if let remountGeneration, remountGeneration != input.remountGeneration { held = nil }
        remountGeneration = input.remountGeneration
        canScroll = input.canScroll
        guard canScroll else { return nil }
        defer { held = nil }
        return held
    }
}
