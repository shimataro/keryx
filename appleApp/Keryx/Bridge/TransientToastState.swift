import Foundation
import Observation

/// A short confirmation that shows for a moment and then clears itself — what `TransientToast`
/// draws. A new `show(_:)` replaces the message and restarts the timer, so a second copy in quick
/// succession keeps the toast up for the full time rather than letting the first one's timer hide it.
@MainActor
@Observable
final class TransientToastState {
    private(set) var message: String?
    @ObservationIgnored private var hideTask: Task<Void, Never>?
    @ObservationIgnored private let duration: Duration

    init(duration: Duration = CopyConfirmationTiming.toast) {
        self.duration = duration
    }

    /// Shows `message` and returns the task that hides it once `duration` has passed (cancelled, and
    /// leaving the message alone, when a later `show(_:)` replaces it).
    @discardableResult
    func show(_ message: String) -> Task<Void, Never> {
        hideTask?.cancel()
        self.message = message
        let duration = self.duration
        let task = Task { [weak self] in
            try? await Task.sleep(for: duration)
            guard !Task.isCancelled else { return }
            self?.message = nil
        }
        hideTask = task
        return task
    }
}

/// How long the app's copy confirmations stay on screen — named in this one place.
enum CopyConfirmationTiming {
    /// The iOS "URL copied" toast (`TransientToastState`'s default).
    static let toast: Duration = .seconds(2)
    /// The reader's ✓ after a copy (`ArticleDetailView`) — as long as Compose's own
    /// `COPIED_FEEDBACK_MS` (`ArticleDetailPane.kt`).
    static let copiedCheck: Duration = .milliseconds(1500)
}
