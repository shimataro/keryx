/// Keeps a pull-to-refresh indicator up for at least `minimum`: the system starts the refresh as
/// soon as the pull crosses its threshold, while the finger is still down, and a refresh that
/// finishes before the finger lifts (tens of milliseconds with nothing new and no cloud connected)
/// would otherwise dismiss the indicator mid-pull, leaving only the content pulled down
/// (`external-spec.md` §9). Kept free of SwiftUI so the standalone `KeryxTests` bundle can compile
/// it directly.
enum PullRefreshHold {
    static let defaultMinimum: Duration = .seconds(1)

    /// Runs `work`, then waits out whatever is left of `minimum`. Only the indicator is held up —
    /// the refresh itself finishes when it finishes. A cancelled task (the list going away) skips
    /// the wait. Runs in the caller's isolation, so `work` may touch main-actor state.
    static func run(
        minimum: Duration = defaultMinimum,
        isolation: isolated (any Actor)? = #isolation,
        _ work: () async -> Void
    ) async {
        let clock = ContinuousClock()
        let start = clock.now
        await work()
        let wait = remaining(after: clock.now - start, minimum: minimum)
        if wait > .zero { try? await Task.sleep(for: wait) }
    }

    /// How much of `minimum` is left once `elapsed` has passed — never negative.
    static func remaining(after elapsed: Duration, minimum: Duration) -> Duration {
        max(minimum - elapsed, .zero)
    }
}
