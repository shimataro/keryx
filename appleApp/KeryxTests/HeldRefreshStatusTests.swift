import Testing

/// Covers `HeldRefreshStatus`, which keeps the iOS toolbar's refresh status up long enough to see.
@MainActor
@Suite
struct HeldRefreshStatusTests {
    /// A clock the test moves by hand, so the elapsed time never depends on how fast the test runs.
    private final class ManualClock {
        var now = ContinuousClock.now
    }

    @Test
    func aQuickRefreshStaysUpUntilTheMinimumHasPassed() async {
        let clock = ManualClock()
        let held = HeldRefreshStatus(minimum: .seconds(2), now: { clock.now })

        held.update(.refreshing)
        clock.now += .milliseconds(1990)
        let clear = held.update(nil)
        #expect(held.status == .refreshing, "a refresh that ended at once must still be shown")

        await clear?.value
        #expect(held.status == nil)
    }

    @Test
    func aLongRefreshClearsAsSoonAsItEnds() {
        let clock = ManualClock()
        let held = HeldRefreshStatus(minimum: .seconds(2), now: { clock.now })

        held.update(.refreshing)
        clock.now += .seconds(3)
        let clear = held.update(nil)

        #expect(clear == nil)
        #expect(held.status == nil)
    }

    @Test
    func newWorkDuringTheHoldKeepsTheStatusUp() async {
        let clock = ManualClock()
        let held = HeldRefreshStatus(minimum: .seconds(60), now: { clock.now })

        held.update(.refreshing)
        let clear = held.update(nil)
        held.update(.syncing)
        await clear?.value

        #expect(held.status == .syncing, "the cancelled hold must not clear the new status")
    }

    @Test
    func aPhaseChangeKeepsTheOriginalStart() {
        let clock = ManualClock()
        let held = HeldRefreshStatus(minimum: .seconds(2), now: { clock.now })

        held.update(.refreshing)
        clock.now += .seconds(1)
        held.update(.syncing)
        #expect(held.status == .syncing)

        clock.now += .seconds(1)
        let clear = held.update(nil)
        #expect(clear == nil, "the minimum counts from when the status first showed")
        #expect(held.status == nil)
    }
}
