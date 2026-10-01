/// Whether the article list offers pull-to-refresh — Compose's own `pullRefreshAvailable`
/// (`HomeCommon.kt`) minus its touch-primary gate, since only the iOS list attaches it at all. Kept
/// free of SwiftUI so the standalone `KeryxTests` bundle can compile it directly.
enum PullToRefreshAvailability {
    /// Never over search results (a pull there is not a request to refresh the feeds behind them)
    /// and never with no feeds at all, where there is nothing to refresh.
    static func isAvailable(searchActive: Bool, hasFeeds: Bool) -> Bool {
        !searchActive && hasFeeds
    }
}
