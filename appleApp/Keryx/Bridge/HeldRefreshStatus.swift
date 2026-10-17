import Foundation
import Observation

/// How long the iOS toolbar's refresh status stays up at the least — named in this one place.
enum RefreshStatusTiming {
    /// As long as the URL-copy toast (`CopyConfirmationTiming.toast`): a refresh with nothing new
    /// finishes in well under a second, too quickly to be seen at all.
    static let minimum: Duration = .seconds(2)
}

/// The refresh status the iOS toolbar shows (`RefreshStatusToolbarItem`), held on screen for at
/// least `minimum` once it appears so a refresh that ends at once is still noticed — the same idea
/// as `PullRefreshHold` for the pull indicator. Work that runs longer clears as soon as it ends.
/// One instance (`HomeObservable.refreshStatus`) feeds both columns, so they never disagree.
@MainActor
@Observable
final class HeldRefreshStatus {
    private(set) var status: RefreshStatus?
    @ObservationIgnored private var shownSince: ContinuousClock.Instant?
    @ObservationIgnored private var clearTask: Task<Void, Never>?
    @ObservationIgnored private let minimum: Duration
    @ObservationIgnored private let now: () -> ContinuousClock.Instant

    init(
        minimum: Duration = RefreshStatusTiming.minimum,
        now: @escaping () -> ContinuousClock.Instant = { ContinuousClock.now }
    ) {
        self.minimum = minimum
        self.now = now
    }

    /// Takes the latest status straight from the activity. A status shows at once (a change of
    /// phase only changes the text, keeping the original start); `nil` clears it once `minimum` has
    /// passed since it first showed, returning the task that does so when that is still ahead.
    @discardableResult
    func update(_ raw: RefreshStatus?) -> Task<Void, Never>? {
        if let raw {
            clearTask?.cancel()
            clearTask = nil
            if shownSince == nil { shownSince = now() }
            status = raw
            return nil
        }
        guard let shownSince, clearTask == nil else { return clearTask }
        let wait = PullRefreshHold.remaining(after: now() - shownSince, minimum: minimum)
        guard wait > .zero else {
            clear()
            return nil
        }
        let task = Task { [weak self] in
            try? await Task.sleep(for: wait)
            guard !Task.isCancelled else { return }
            self?.clear()
        }
        clearTask = task
        return task
    }

    private func clear() {
        status = nil
        shownSince = nil
        clearTask = nil
    }
}
