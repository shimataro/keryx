import Testing

/// `PullRefreshHold` keeps the pull-to-refresh indicator up for a minimum time without delaying the
/// refresh itself.
@Suite
struct PullRefreshHoldTests {
    private let clock = ContinuousClock()
    private let minimum: Duration = .milliseconds(200)

    @Test
    func instantWorkIsHeldUpForTheMinimum() async {
        let elapsed = await clock.measure {
            await PullRefreshHold.run(minimum: minimum) {}
        }
        #expect(elapsed >= minimum)
    }

    @Test
    func returnsOnlyAfterTheWorkHasFinished() async {
        var finished = false
        await PullRefreshHold.run(minimum: .zero) {
            try? await Task.sleep(for: .milliseconds(50))
            finished = true
        }
        #expect(finished)
    }

    @Test
    func instantWorkWaitsOutTheWholeMinimum() {
        #expect(PullRefreshHold.remaining(after: .zero, minimum: minimum) == minimum)
    }

    @Test
    func workShorterThanTheMinimumWaitsOutOnlyTheRest() {
        #expect(PullRefreshHold.remaining(after: .milliseconds(150), minimum: minimum) == .milliseconds(50))
    }

    @Test
    func workOfExactlyTheMinimumWaitsNoLonger() {
        #expect(PullRefreshHold.remaining(after: minimum, minimum: minimum) == .zero)
    }

    @Test
    func workLongerThanTheMinimumIsNotHeldUpFurther() {
        #expect(PullRefreshHold.remaining(after: .milliseconds(500), minimum: minimum) == .zero)
    }
}
