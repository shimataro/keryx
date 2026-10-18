/// What the iOS toolbar's status area says about a feed refresh the user started (`RefreshStatusLabel`):
/// that new articles are being checked for — a pull, ⌘R, a feed's Refresh, never an automatic
/// refresh — and then how the check went. A sync on its own never shows here; the sync button's
/// spinning icon is its feedback (`external-spec.md` §9). Kept free of SwiftUI so the
/// standalone `KeryxTests` bundle can compile it directly.
enum RefreshStatus: Equatable {
    case checking
    case newArticles(Int)
    case noNewArticles
    case refreshFailed

    /// The result a finished check (the shared `ManualRefreshStatus.Finished`'s outcome) shows.
    static func result(newArticleCount: Int, unreachable: Bool) -> RefreshStatus {
        if unreachable { return .refreshFailed }
        return newArticleCount > 0 ? .newArticles(newArticleCount) : .noNewArticles
    }
}
