import Foundation
import Observation

/// How long the iOS toolbar's refresh status keeps each state — named in this one place.
enum RefreshStatusTiming {
    /// How long a finished check's result stays up — a little longer than the URL-copy toast
    /// (`CopyConfirmationTiming.toast`), since it can be a sentence to read.
    static let result: Duration = .milliseconds(2500)
    /// How long "checking" waits, once the check has ended, for its result to arrive. The shared
    /// code reports the result only after the activity has gone idle, so without this the status
    /// would vanish for a moment between the two.
    static let resultGrace: Duration = .milliseconds(500)
}

/// The refresh status the iOS toolbar shows (`RefreshStatusToolbarItem`): "checking" while a check
/// runs, then its result for a moment. One instance (`HomeObservable.refreshStatus`) feeds both
/// columns, so they never disagree.
@MainActor
@Observable
final class HeldRefreshStatus {
    private(set) var status: RefreshStatus?
    @ObservationIgnored private var clearTask: Task<Void, Never>?
    @ObservationIgnored private let resultDuration: Duration
    @ObservationIgnored private let resultGrace: Duration

    init(resultDuration: Duration = RefreshStatusTiming.result, resultGrace: Duration = RefreshStatusTiming.resultGrace) {
        self.resultDuration = resultDuration
        self.resultGrace = resultGrace
    }

    /// Takes whether a check is in flight (`RefreshStatus.isChecking`). A check that starts replaces
    /// whatever is shown; one that ends leaves "checking" up for `resultGrace`, for its result to
    /// take over. A result on screen is left alone when the activity goes idle. Returns the task
    /// that clears the status, when one was scheduled.
    @discardableResult
    func updateChecking(_ checking: Bool) -> Task<Void, Never>? {
        if checking {
            cancelClear()
            status = .checking
            return nil
        }
        guard status == .checking, clearTask == nil else { return nil }
        return scheduleClear(after: resultGrace)
    }

    /// Shows a finished check's result (`RefreshStatus.result`) for `resultDuration`, returning the
    /// task that then clears it.
    @discardableResult
    func showResult(_ result: RefreshStatus) -> Task<Void, Never> {
        cancelClear()
        status = result
        return scheduleClear(after: resultDuration)
    }

    private func scheduleClear(after delay: Duration) -> Task<Void, Never> {
        let task = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, let self else { return }
            self.status = nil
            self.clearTask = nil
        }
        clearTask = task
        return task
    }

    private func cancelClear() {
        clearTask?.cancel()
        clearTask = nil
    }
}
