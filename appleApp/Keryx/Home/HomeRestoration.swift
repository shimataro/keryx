/// What the iOS Home screen restores after iOS terminated the app in the background — the collapsed
/// stack's column (`CompactColumnRestoration`) and the reader's scroll position
/// (`ReaderScrollPosition`) — and when it stops offering them. Kept free of SwiftUI so the standalone
/// `KeryxTests` bundle can compile it directly; `HomeView` only passes its events in.
///
/// Both are checked against the selection `HomeViewModel` restored, which `HomeObservable` mirrors
/// from its first frame. The reader position is offered only until the user selects another article
/// or navigates the stack, so it restores the relaunch rather than every later reopening of the same
/// article.
struct HomeRestoration {
    typealias Column = CompactSearchNavigation.Column

    /// Whether the restored column has been checked against the restored selection — once per view.
    private var columnChecked = false
    /// Set when that check moved the column, so the move is not taken for the user's own navigation.
    private var restoredColumnChangePending = false
    /// Whether the stored reader position is still offered to the reader.
    private(set) var readerScrollPending = true

    /// The view appeared on `storedColumn`, the column scene storage restored: the column to move to
    /// instead, if any — the article list for a reader restored without its article. Only the first
    /// call checks.
    mutating func appeared(storedColumn: Column, hasSelectedArticle: Bool) -> Column? {
        guard !columnChecked else { return nil }
        columnChecked = true
        let restored = CompactColumnRestoration.restored(storedColumn, hasSelectedArticle: hasSelectedArticle)
        guard restored != storedColumn else { return nil }
        restoredColumnChangePending = true
        return restored
    }

    /// The collapsed stack moved to another column. Returns whether the user moved it — false for the
    /// move `appeared` asked for, which must neither rebuild the article list nor flash a row. The
    /// user's own navigation withdraws the stored reader position.
    mutating func columnChanged() -> Bool {
        if restoredColumnChangePending {
            restoredColumnChangePending = false
            return false
        }
        readerScrollPending = false
        return true
    }

    /// The selected article changed from `old` to `new`. Only a change away from a selection withdraws
    /// the stored reader position: the restored selection arriving (nil → id) is no choice of the user's.
    mutating func selectionChanged(from old: String?, to new: String?) {
        guard old != nil, old != new else { return }
        readerScrollPending = false
    }

    /// The reader position to offer, given what scene storage holds — nil once it is withdrawn.
    func readerScroll(articleId: String, fraction: Double) -> ReaderScrollPosition? {
        guard readerScrollPending else { return nil }
        return ReaderScrollPosition.stored(articleId: articleId, fraction: fraction)
    }
}
