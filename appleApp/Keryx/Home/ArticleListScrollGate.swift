/// Holds the iOS article list's scrolls (`ArticleListView`) while the list cannot carry them out.
///
/// A scroll asked for while the list is under the reader (a swipe in the reader moves the selection, a
/// restored selection lands) is not for the list to act on then — it is not on screen, and a list
/// built before its window attachment is rebuilt, which would lose the scroll with it. So it is held,
/// and only the latest one is passed on when the list can take it, provided its row still exists.
/// (v0.27.0 crashed in `UICollectionView`'s scroll target validation when such a scroll waited for
/// the back navigation's first layout; `ArticleCollectionView` no longer leaves a scroll waiting on
/// SwiftUI, but a scroll to the row the user just left still belongs to the moment the list returns.)
/// See "Article list (iOS)" in `docs/app-architecture.md`.
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
