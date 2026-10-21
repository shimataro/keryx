import Testing

/// `ReaderRebuildThrottle` makes only a burst of article switches wait, never a single click.
@Suite
struct ReaderRebuildThrottleTests {
    private let start = ContinuousClock.now

    @Test
    func theFirstArticleBuildsAtOnce() {
        var throttle = ReaderRebuildThrottle()
        #expect(throttle.delay(for: "a1", now: start) == nil)
    }

    @Test
    func aSwitchAfterAQuietSpellBuildsAtOnce() {
        var throttle = ReaderRebuildThrottle()
        _ = throttle.delay(for: "a1", now: start)
        #expect(throttle.delay(for: "a2", now: start + .milliseconds(500)) == nil)
    }

    @Test
    func aSwitchWithinTheWindowOfThePreviousOneWaits() {
        var throttle = ReaderRebuildThrottle()
        _ = throttle.delay(for: "a1", now: start)
        #expect(throttle.delay(for: "a2", now: start + .milliseconds(30)) == ReaderRebuildThrottle.window)
        // The burst goes on: each further quick switch waits too, measured from the one before it.
        #expect(throttle.delay(for: "a3", now: start + .milliseconds(90)) == ReaderRebuildThrottle.window)
    }

    @Test
    func aRebuildOfTheSameArticleNeverWaits() {
        var throttle = ReaderRebuildThrottle()
        _ = throttle.delay(for: "a1", now: start)
        _ = throttle.delay(for: "a2", now: start + .milliseconds(10))
        // A star toggle or theme change on the article just switched to, inside the window.
        #expect(throttle.delay(for: "a2", now: start + .milliseconds(20)) == nil)
    }

    @Test
    func leavingAnArticleForTheBlankStateCountsAsASwitch() {
        var throttle = ReaderRebuildThrottle()
        _ = throttle.delay(for: "a1", now: start)
        #expect(throttle.delay(for: nil, now: start + .milliseconds(10)) == ReaderRebuildThrottle.window)
        #expect(throttle.delay(for: nil, now: start + .milliseconds(20)) == nil)
    }
}
