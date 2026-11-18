import Testing

/// `NextcloudServerCheckGate` hands a passed server check on only while the sheet is still open, so a
/// check that passes after Cancel never starts the sign-in.
@MainActor
@Suite
struct NextcloudServerCheckGateTests {
    /// A check whose result the test releases by hand, standing in for a slow server probe.
    @MainActor
    private final class PendingCheck {
        private var continuation: CheckedContinuation<Int?, Never>?
        private let (started, signalStarted) = AsyncStream<Void>.makeStream()
        private(set) var finished = false

        func run() async -> Int? {
            let value = await withCheckedContinuation { continuation = $0; signalStarted.yield() }
            finished = true
            return value
        }

        func awaitStarted() async {
            for await _ in started { break }
        }

        func finish(with value: Int?) {
            continuation?.resume(returning: value)
            continuation = nil
        }
    }

    @Test
    func closingWhileTheCheckRunsDropsItsResult() async {
        let gate = NextcloudServerCheckGate<Int>()
        let check = PendingCheck()
        var passed: [Int] = []
        let task = gate.start(check: { await check.run() }) { passed.append($0) }
        await check.awaitStarted()

        gate.close()
        check.finish(with: 1)
        await task?.value

        #expect(check.finished)
        #expect(passed.isEmpty)
    }

    @Test
    func aPassedCheckIsHandedOnOnce() async {
        let gate = NextcloudServerCheckGate<Int>()
        let check = PendingCheck()
        var passed: [Int] = []
        let task = gate.start(check: { await check.run() }) { passed.append($0) }
        await check.awaitStarted()

        check.finish(with: 7)
        await task?.value

        #expect(passed == [7])
    }

    @Test
    func aCheckThatProducedNothingIsNotHandedOn() async {
        let gate = NextcloudServerCheckGate<Int>()
        let check = PendingCheck()
        var passed: [Int] = []
        let task = gate.start(check: { await check.run() }) { passed.append($0) }
        await check.awaitStarted()

        check.finish(with: nil)
        await task?.value

        #expect(check.finished)
        #expect(passed.isEmpty)
    }

    @Test
    func startingAfterCloseDoesNothing() async {
        let gate = NextcloudServerCheckGate<Int>()
        var ran = false
        var passed: [Int] = []
        gate.close()

        let task = gate.start(check: { ran = true; return 1 }) { passed.append($0) }

        #expect(task == nil)
        #expect(!ran)
        #expect(passed.isEmpty)
    }

    @Test
    func aNewerCheckReplacesTheOneStillRunning() async {
        let gate = NextcloudServerCheckGate<Int>()
        let first = PendingCheck()
        let second = PendingCheck()
        var passed: [Int] = []
        let firstTask = gate.start(check: { await first.run() }) { passed.append($0) }
        await first.awaitStarted()
        let secondTask = gate.start(check: { await second.run() }) { passed.append($0) }
        await second.awaitStarted()

        first.finish(with: 1)
        second.finish(with: 2)
        await firstTask?.value
        await secondTask?.value

        #expect(first.finished)
        #expect(passed == [2])
    }
}
