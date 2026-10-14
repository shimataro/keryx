import Testing

/// `PullRefreshHold` keeps the pull-to-refresh indicator up for a minimum time without delaying the
/// refresh itself.
@Suite
struct PullRefreshHoldTests {
    private let clock = ContinuousClock()

    @Test
    func instantWorkIsHeldUpForTheMinimum() async {
        let elapsed = await clock.measure {
            await PullRefreshHold.run(minimum: .milliseconds(200)) {}
        }
        #expect(elapsed >= .milliseconds(200))
    }

    @Test
    func workLongerThanTheMinimumIsNotHeldUpFurther() async {
        let elapsed = await clock.measure {
            await PullRefreshHold.run(minimum: .milliseconds(100)) {
                try? await Task.sleep(for: .milliseconds(300))
            }
        }
        #expect(elapsed >= .milliseconds(300))
        #expect(elapsed < .milliseconds(600))
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
}
