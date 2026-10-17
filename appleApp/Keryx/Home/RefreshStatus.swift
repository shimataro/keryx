/// What the iOS toolbar's status area says about a feed refresh (`RefreshStatusLabel`): that new
/// articles are being checked for — whatever started it: the pull, ⌘R, startup maintenance, a
/// background refresh — and then how the check went. A sync on its own never shows here; the sync
/// button's spinning icon is its feedback (`external-spec.md` §9). Kept free of SwiftUI so the
/// standalone `KeryxTests` bundle can compile it directly.
enum RefreshStatus: Equatable {
    case checking
    case newArticles(Int)
    case noNewArticles
    case refreshFailed

    /// Whether a check is in flight, from `ActivitySnapshot`: a feed refresh, or a refresh-then-sync
    /// cycle — whose sync still counts as checking, as the pull indicator stays up through it too —
    /// but never a sync on its own.
    static func isChecking(feedRefreshing: Bool, refreshCycleRunning: Bool) -> Bool {
        feedRefreshing || refreshCycleRunning
    }

    /// The result a finished check (a shared `RefreshOutcome`) shows.
    static func result(newArticleCount: Int, unreachable: Bool) -> RefreshStatus {
        if unreachable { return .refreshFailed }
        return newArticleCount > 0 ? .newArticles(newArticleCount) : .noNewArticles
    }
}
