import Testing

/// Covers `TransientToastState`, the iOS URL-copy confirmation's show-then-clear timing.
@MainActor
@Suite
struct TransientToastStateTests {
    @Test
    func aMessageShowsAtOnceAndClearsAfterItsDuration() async {
        let state = TransientToastState(duration: .milliseconds(10))

        let hide = state.show("URL copied")
        #expect(state.message == "URL copied")

        await hide.value
        #expect(state.message == nil)
    }

    @Test
    func aNewMessageRestartsTheTimer() async {
        let state = TransientToastState(duration: .seconds(60))

        let first = state.show("first")
        let second = state.show("second")
        await first.value

        #expect(state.message == "second", "the replaced message's timer must not hide the new one")
        second.cancel()
    }
}
