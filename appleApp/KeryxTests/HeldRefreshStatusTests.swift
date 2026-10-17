import Testing

/// Covers `HeldRefreshStatus`: "checking" while a check runs, then its result for a moment.
@MainActor
@Suite
struct HeldRefreshStatusTests {
    @Test
    func aCheckShowsCheckingThenItsResultUntilTheResultTimesOut() async {
        let held = HeldRefreshStatus(resultDuration: .milliseconds(10), resultGrace: .seconds(60))

        held.updateChecking(true)
        #expect(held.status == .checking)

        let grace = held.updateChecking(false)
        #expect(held.status == .checking, "checking waits for its result rather than vanishing")

        let clear = held.showResult(.noNewArticles)
        await grace?.value
        #expect(held.status == .noNewArticles, "the result cancels the grace period's clear")

        await clear.value
        #expect(held.status == nil)
    }

    @Test
    func aCheckWithNoResultClearsAfterTheGrace() async {
        let held = HeldRefreshStatus(resultDuration: .seconds(60), resultGrace: .milliseconds(10))

        held.updateChecking(true)
        let grace = held.updateChecking(false)
        await grace?.value

        #expect(held.status == nil)
    }

    @Test
    func goingIdleLeavesAResultUp() {
        let held = HeldRefreshStatus(resultDuration: .seconds(60), resultGrace: .milliseconds(10))

        held.showResult(.newArticles(2))
        let clear = held.updateChecking(false)

        #expect(clear == nil)
        #expect(held.status == .newArticles(2))
    }

    @Test
    func aNewCheckReplacesTheResult() async {
        let held = HeldRefreshStatus(resultDuration: .milliseconds(10), resultGrace: .seconds(60))

        let clear = held.showResult(.refreshFailed)
        held.updateChecking(true)
        await clear.value

        #expect(held.status == .checking, "the replaced result's timer must not clear the new check")
    }

    @Test
    func nothingShowsWhenNoCheckRan() {
        let held = HeldRefreshStatus()

        #expect(held.updateChecking(false) == nil)
        #expect(held.status == nil)
    }
}
