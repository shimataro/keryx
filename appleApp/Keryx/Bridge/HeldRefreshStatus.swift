import Foundation
import Observation

/// How long the iOS toolbar's refresh status keeps a result — named in this one place.
enum RefreshStatusTiming {
    /// How long a finished check's result stays up — a little longer than the URL-copy toast
    /// (`CopyConfirmationTiming.toast`), since it can be a sentence to read.
    static let result: Duration = .milliseconds(2500)
}

/// The refresh status the iOS toolbar shows (`RefreshStatusToolbarItem`): "checking" while a refresh
/// the user started runs, then its result for a moment. Mirrors the shared `ManualRefreshStatus`,
/// which already decides when a check starts and ends; this only adds how long a result stays up. One
/// instance (`HomeObservable.refreshStatus`) feeds both columns, so they never disagree.
@MainActor
@Observable
final class HeldRefreshStatus {
    private(set) var status: RefreshStatus?
    @ObservationIgnored private var clearTask: Task<Void, Never>?
    @ObservationIgnored private let resultDuration: Duration

    init(resultDuration: Duration = RefreshStatusTiming.result) {
        self.resultDuration = resultDuration
    }

    /// Takes the shared status: `.checking` replaces whatever is shown; a result shows for
    /// `resultDuration`; `nil` (idle — the check refreshed no feed) clears a check but leaves a result
    /// to time out. Returns the task that clears a result, when one was scheduled.
    @discardableResult
    func update(_ newStatus: RefreshStatus?) -> Task<Void, Never>? {
        switch newStatus {
        case .checking:
            cancelClear()
            // Unchanged writes still invalidate observers, so skip them.
            if status != .checking { status = .checking }
            return nil
        case nil:
            if status == .checking { status = nil }
            return nil
        case let result?:
            cancelClear()
            status = result
            let task = Task { [weak self, resultDuration] in
                try? await Task.sleep(for: resultDuration)
                guard !Task.isCancelled, let self else { return }
                self.status = nil
                self.clearTask = nil
            }
            clearTask = task
            return task
        }
    }

    private func cancelClear() {
        clearTask?.cancel()
        clearTask = nil
    }
}
