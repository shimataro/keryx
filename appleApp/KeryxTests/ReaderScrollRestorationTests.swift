import Testing

@MainActor
@Suite
struct ReaderScrollRestorationTests {
    private let start = ContinuousClock.now

    @Test
    func aReloadReturnsToTheLastRecordedPositionOnceLoaded() {
        let restoration = ReaderScrollRestoration()
        restoration.documentReplaced()
        restoration.scrolled(to: 0.4, now: start)
        restoration.reloadStarted()
        #expect(restoration.documentFinished(now: start) == 0.4)
    }

    @Test
    func aReloadAtTheTopRestoresNothing() {
        let restoration = ReaderScrollRestoration()
        restoration.documentReplaced()
        restoration.scrolled(to: 0.4, now: start)
        restoration.scrolled(to: 0, now: start)
        restoration.reloadStarted()
        #expect(restoration.documentFinished(now: start) == nil)
    }

    @Test
    func aFinishedLoadWithoutAReloadRestoresNothing() {
        let restoration = ReaderScrollRestoration()
        restoration.documentReplaced()
        restoration.scrolled(to: 0.4, now: start)
        #expect(restoration.documentFinished(now: start) == nil)
        #expect(restoration.contentResized(now: start) == nil)
    }

    @Test
    func aNewDocumentForgetsThePreviousPosition() {
        let restoration = ReaderScrollRestoration()
        restoration.documentReplaced()
        restoration.scrolled(to: 0.4, now: start)
        restoration.documentReplaced()
        restoration.reloadStarted()
        #expect(restoration.documentFinished(now: start) == nil)
    }

    @Test
    func aNewDocumentWithAnInitialPositionScrollsThereOnceLoaded() {
        let restoration = ReaderScrollRestoration()
        restoration.documentReplaced(restoring: 0.7)
        #expect(restoration.lastFraction == 0.7)
        #expect(restoration.documentFinished(now: start) == 0.7)
    }

    @Test
    func anInitialPositionIsClampedAndTheTopOrAnInvalidValueRestoresNothing() {
        let restoration = ReaderScrollRestoration()
        restoration.documentReplaced(restoring: 1.5)
        #expect(restoration.documentFinished(now: start) == 1)
        restoration.documentReplaced(restoring: 0)
        #expect(restoration.documentFinished(now: start) == nil)
        restoration.documentReplaced(restoring: .nan)
        #expect(restoration.documentFinished(now: start) == nil)
        restoration.documentReplaced(restoring: -0.2)
        #expect(restoration.documentFinished(now: start) == nil)
    }

    @Test
    func growthWithinTheSettleWindowReappliesThePosition() {
        let restoration = ReaderScrollRestoration()
        restoration.documentReplaced(restoring: 0.5)
        _ = restoration.documentFinished(now: start)
        #expect(restoration.contentResized(now: start + .seconds(1)) == 0.5)
        #expect(restoration.contentResized(now: start + .seconds(2)) == 0.5)
    }

    @Test
    func growthAfterTheSettleWindowLeavesThePageAlone() {
        let restoration = ReaderScrollRestoration()
        restoration.documentReplaced(restoring: 0.5)
        _ = restoration.documentFinished(now: start)
        #expect(restoration.contentResized(now: start + ReaderScrollRestoration.settleWindow) == nil)
    }

    @Test
    func theUsersOwnInputEndsTheRestore() {
        let restoration = ReaderScrollRestoration()
        restoration.documentReplaced(restoring: 0.5)
        _ = restoration.documentFinished(now: start)
        restoration.userInteracted()
        #expect(restoration.contentResized(now: start) == nil)
        restoration.scrolled(to: 0.2, now: start)
        #expect(restoration.lastFraction == 0.2)
    }

    @Test
    func theReadersOwnScrollWhileRestoringIsNotRecorded() {
        let restoration = ReaderScrollRestoration()
        restoration.documentReplaced(restoring: 0.5)
        restoration.scrolled(to: 0.1, now: start)
        _ = restoration.documentFinished(now: start)
        restoration.scrolled(to: 0.3, now: start + .seconds(1))
        #expect(restoration.lastFraction == 0.5)
        #expect(restoration.contentResized(now: start + .seconds(1)) == 0.5)
    }

    @Test
    func scrollingIsRecordedAgainOnceTheSettleWindowPassed() {
        let restoration = ReaderScrollRestoration()
        restoration.documentReplaced(restoring: 0.5)
        _ = restoration.documentFinished(now: start)
        restoration.scrolled(to: 0.6, now: start + ReaderScrollRestoration.settleWindow)
        #expect(restoration.lastFraction == 0.6)
    }

    @Test
    func aSecondReloadReturnsToTheSamePositionEvenIfTheFirstNeverFinished() {
        let restoration = ReaderScrollRestoration()
        restoration.documentReplaced()
        restoration.scrolled(to: 0.4, now: start)
        restoration.reloadStarted()
        restoration.reloadStarted()
        #expect(restoration.documentFinished(now: start) == 0.4)
    }

    @Test
    func aRecordedScrollIsReportedBackAndAnIgnoredOneIsNot() {
        let restoration = ReaderScrollRestoration()
        restoration.documentReplaced()
        #expect(restoration.scrolled(to: 0.4, now: start) == 0.4)
        #expect(restoration.scrolled(to: 0, now: start) == 0)
        #expect(restoration.scrolled(to: 1.2, now: start) == 1)
        restoration.reloadStarted()
        #expect(restoration.scrolled(to: 0.4, now: start) == nil)
    }
}
