import Testing

/// Covers `HeldRefreshStatus`: "checking" while a refresh the user started runs, then its result for a
/// moment.
@MainActor
@Suite
struct HeldRefreshStatusTests {
    @Test
    func aCheckShowsCheckingThenItsResultUntilTheResultTimesOut() async {
        let held = HeldRefreshStatus(resultDuration: .milliseconds(10))

        held.update(.checking)
        #expect(held.status == .checking)

        let clear = held.update(.noNewArticles)
        #expect(held.status == .noNewArticles)

        await clear?.value
        #expect(held.status == nil)
    }

    @Test
    func aCheckThatRefreshedNothingClearsAtOnce() {
        let held = HeldRefreshStatus()

        held.update(.checking)
        held.update(nil)

        #expect(held.status == nil)
    }

    @Test
    func idleLeavesAResultToTimeOut() {
        let held = HeldRefreshStatus(resultDuration: .seconds(60))

        held.update(.newArticles(2))
        held.update(nil)

        #expect(held.status == .newArticles(2))
    }

    @Test
    func aNewCheckReplacesTheResult() async {
        let held = HeldRefreshStatus(resultDuration: .milliseconds(10))

        let clear = held.update(.refreshFailed)
        held.update(.checking)
        await clear?.value

        #expect(held.status == .checking, "the replaced result's timer must not clear the new check")
    }

    @Test
    func aNewResultRestartsTheTimer() async {
        let held = HeldRefreshStatus(resultDuration: .milliseconds(10))

        let first = held.update(.noNewArticles)
        held.update(.checking)
        let second = held.update(.newArticles(1))
        await first?.value

        #expect(held.status == .newArticles(1), "the first result's timer must not clear the second")
        await second?.value
        #expect(held.status == nil)
    }

    @Test
    func nothingShowsWhenNoCheckRan() {
        let held = HeldRefreshStatus()

        held.update(nil)

        #expect(held.status == nil)
    }
}
