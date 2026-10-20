import Testing

/// `AbandonableRefresh` ends a pull-to-refresh wait when the selection it was made for goes away,
/// and otherwise waits the work out.
@MainActor
@Suite
struct AbandonableRefreshTests {
    private let clock = ContinuousClock()
    private let longHold: Duration = .seconds(10)

    @Test
    func abandoningEndsTheWaitAtOnce() async {
        let refresh = AbandonableRefresh()
        let (started, signalStarted) = AsyncStream<Void>.makeStream()
        let elapsed = await clock.measure {
            async let run: () = refresh.run {
                signalStarted.yield()
                await PullRefreshHold.run(minimum: longHold) {}
            }
            // `async let` hops to the main actor before `run` registers its task, so a bare
            // `Task.yield()` could abandon before there was anything to abandon. The work running
            // means `run` has already registered it.
            for await _ in started { break }
            refresh.abandon()
            await run
        }
        #expect(elapsed < longHold / 2)
    }

    @Test
    func waitsForTheWorkWhenNotAbandoned() async {
        let refresh = AbandonableRefresh()
        let finished = Flag()
        await refresh.run {
            try? await Task.sleep(for: .milliseconds(50))
            finished.isSet = true
        }
        #expect(finished.isSet)
    }

    @Test
    func cancellingTheCallerCancelsTheWork() async {
        let refresh = AbandonableRefresh()
        let sawCancellation = Flag()
        let caller = Task {
            await refresh.run {
                try? await Task.sleep(for: .seconds(10))
                sawCancellation.isSet = Task.isCancelled
            }
        }
        caller.cancel()
        await caller.value
        #expect(sawCancellation.isSet)
    }

    @Test
    func abandoningWithNothingRunningDoesNothing() async {
        let refresh = AbandonableRefresh()
        refresh.abandon()
        let finished = Flag()
        await refresh.run { finished.isSet = true }
        #expect(finished.isSet)
    }
}

@MainActor
private final class Flag {
    var isSet = false
}
