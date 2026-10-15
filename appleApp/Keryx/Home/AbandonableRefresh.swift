/// A pull-to-refresh wait that can be given up from outside: the article list's `.refreshable`
/// indicator stays up for as long as `run` has not returned, and that wait belongs to the selection
/// it was made for. Once the selection moves elsewhere, the list on screen is another one's, and an
/// indicator left spinning over it would claim a refresh that is not happening there (Compose's own
/// `pullRefreshing` likewise follows only the current filter). `abandon()` ends the wait — the
/// refresh itself carries on. Kept free of SwiftUI so the standalone `KeryxTests` bundle can
/// compile it directly.
@MainActor
final class AbandonableRefresh {
    private var current: Task<Void, Never>?

    /// Runs `work` and returns once it has finished, been abandoned, or the caller was cancelled.
    /// `work` sees an abandonment as its task's cancellation, which ends `PullRefreshHold`'s wait
    /// and any `for await` it is in.
    func run(_ work: @escaping @MainActor @Sendable () async -> Void) async {
        let task = Task { await work() }
        current = task
        await withTaskCancellationHandler {
            await task.value
        } onCancel: {
            task.cancel()
        }
        if current == task { current = nil }
    }

    /// Ends the wait of the `run` in progress, if any.
    func abandon() {
        current?.cancel()
        current = nil
    }
}
