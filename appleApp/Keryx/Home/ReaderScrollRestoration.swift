import Foundation

/// Decides where the reader scrolls a document back to after loading it again — kept free of WebKit
/// so the standalone `KeryxTests` bundle can compile it directly.
///
/// The page reports its vertical scroll position as a fraction of its scrollable height (0 at the
/// top, 1 at the bottom), and this keeps the last one for the current document. When the document is
/// loaded again to recover from a WebContent termination (see `ReaderProcessRecovery`), or loaded for
/// the first time with a position to restore, the reader scrolls it back to that fraction once the
/// load finishes, then again each time the content grows (images arriving) during `settleWindow` —
/// until the user scrolls, taps or presses a key, which ends the restore at once so the page never
/// jumps away from where the user went. While a restore is running, the scroll the reader itself
/// causes is not recorded.
@MainActor
final class ReaderScrollRestoration {
    /// How long after the load finishes the restore keeps re-applying while the content grows.
    static let settleWindow: Duration = .seconds(3)

    private enum Phase {
        /// Recording the user's scroll position.
        case idle
        /// A load is in flight that should end at `target`.
        case pending(target: Double)
        /// The load finished: re-apply `target` on growth until `deadline`.
        case applying(target: Double, deadline: ContinuousClock.Instant)
    }

    private var phase = Phase.idle
    /// The last recorded position of the current document, if it was scrolled away from the top.
    private(set) var lastFraction: Double?

    /// The reader is about to load a new document because its content changed. `initialFraction` is a
    /// position to scroll that document to once loaded (a position saved before the app was
    /// terminated), or nil to start at the top.
    func documentReplaced(restoring initialFraction: Double? = nil) {
        let target = Self.restorable(initialFraction)
        lastFraction = target
        phase = target.map { .pending(target: $0) } ?? .idle
    }

    /// The reader is about to load the current document again (after a WebContent termination, or on
    /// becoming active after giving up on it): scroll it back to the last recorded position.
    func reloadStarted() {
        guard let target = lastFraction else {
            phase = .idle
            return
        }
        phase = .pending(target: target)
    }

    /// The page reports it is now scrolled to `fraction`. Returns the position recorded (clamped to
    /// 0...1), or nil when it was not recorded because a restore is running.
    @discardableResult
    func scrolled(to fraction: Double, now: ContinuousClock.Instant) -> Double? {
        expireIfDue(now: now)
        guard case .idle = phase, fraction.isFinite else { return nil }
        lastFraction = Self.restorable(fraction)
        return lastFraction ?? 0
    }

    /// The document finished loading: the fraction to scroll it to now, if any.
    func documentFinished(now: ContinuousClock.Instant) -> Double? {
        guard case let .pending(target) = phase else { return nil }
        phase = .applying(target: target, deadline: now + Self.settleWindow)
        return target
    }

    /// The document's content changed size: the fraction to scroll it to again, if a restore is still
    /// settling.
    func contentResized(now: ContinuousClock.Instant) -> Double? {
        expireIfDue(now: now)
        guard case let .applying(target, _) = phase else { return nil }
        return target
    }

    /// The user scrolled, tapped or pressed a key in the page: any restore ends here.
    func userInteracted() {
        phase = .idle
    }

    private func expireIfDue(now: ContinuousClock.Instant) {
        if case let .applying(_, deadline) = phase, now >= deadline { phase = .idle }
    }

    /// `fraction` clamped to 0...1, or nil where there is nothing to restore (the top, or no value).
    private static func restorable(_ fraction: Double?) -> Double? {
        guard let fraction, fraction.isFinite else { return nil }
        let clamped = min(max(fraction, 0), 1)
        return clamped > 0 ? clamped : nil
    }
}
