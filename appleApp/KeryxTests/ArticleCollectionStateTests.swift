import CoreGraphics
import Testing

/// Covers the UIKit-free state of the iOS article list (`ArticleCollectionState.swift`): each scroll
/// request carried out once, the position a change made off screen is put back to, and when the row
/// height is measured again.
@Suite
struct ArticleCollectionStateTests {
    private let target = ArticleListScrollGate.Target(rowId: "a1")

    @Test
    func aScrollRequestIsTakenOncePerGeneration() {
        var tracker = ScrollRequestTracker(generation: 3)
        #expect(tracker.take(ArticleListScrollRequest(generation: 3, kind: .reveal(target))) == nil)
        #expect(tracker.take(ArticleListScrollRequest(generation: 4, kind: .reveal(target))) == .reveal(target))
        #expect(tracker.take(ArticleListScrollRequest(generation: 4, kind: .freshEnd(newestFirst: true))) == nil)
        #expect(tracker.take(ArticleListScrollRequest(generation: 5, kind: .freshEnd(newestFirst: false)))
            == .freshEnd(newestFirst: false))
    }

    @Test
    func aNewGenerationWithoutAKindIsRecordedButYieldsNothing() {
        var tracker = ScrollRequestTracker(generation: 0)
        #expect(tracker.take(ArticleListScrollRequest(generation: 1, kind: nil)) == nil)
        #expect(tracker.take(ArticleListScrollRequest(generation: 1, kind: .reveal(target))) == nil)
    }

    @Test
    func theFirstDeferredPositionIsKeptWhileAResetAddsUp() {
        var keeper = DeferredPositionKeeper()
        #expect(keeper.take() == nil)
        let first = ScrollAnchor(id: "a1", offset: 10)
        keeper.record(anchor: first, oldIds: ["a0", "a1"], reset: false)
        keeper.record(anchor: ScrollAnchor(id: "b1", offset: 0), oldIds: ["b1"], reset: true)
        keeper.record(anchor: nil, oldIds: [], reset: false)
        #expect(keeper.take() == DeferredPosition(anchor: first, oldIds: ["a0", "a1"], reset: true))
        #expect(keeper.take() == nil)
    }

    @Test
    func aTakenPositionLetsTheNextChangeStartAfresh() {
        var keeper = DeferredPositionKeeper()
        keeper.record(anchor: nil, oldIds: [], reset: true)
        _ = keeper.take()
        let anchor = ScrollAnchor(id: "a2", offset: 0)
        keeper.record(anchor: anchor, oldIds: ["a2"], reset: false)
        #expect(keeper.take() == DeferredPosition(anchor: anchor, oldIds: ["a2"], reset: false))
    }

    @Test
    func aResetMarksThePendingPositionWithoutReplacingItsAnchor() {
        var keeper = DeferredPositionKeeper()
        let anchor = ScrollAnchor(id: "a1", offset: 10)
        keeper.record(anchor: anchor, oldIds: ["a0", "a1"], reset: false)
        keeper.markReset()
        #expect(keeper.take() == DeferredPosition(anchor: anchor, oldIds: ["a0", "a1"], reset: true))
    }

    @Test
    func aResetWithNothingPendingLeavesNothingPending() {
        var keeper = DeferredPositionKeeper()
        keeper.markReset()
        #expect(keeper.take() == nil)
    }

    @Test
    func theRowHeightIsMeasuredOnceOnScreenAndAgainForANewCategory() {
        var policy = RowHeightMeasurePolicy()
        #expect(!policy.needsMeasure(isActive: false, category: "L"))
        #expect(policy.needsMeasure(isActive: true, category: "L"))
        policy.didMeasure(category: "L")
        #expect(policy.measured)
        #expect(!policy.needsMeasure(isActive: true, category: "L"))
        #expect(policy.needsMeasure(isActive: true, category: "XL"))
        #expect(!policy.needsMeasure(isActive: false, category: "XL"))
    }

    @Test
    func leavingTheWindowMeansMeasuringAgain() {
        var policy = RowHeightMeasurePolicy()
        policy.didMeasure(category: "L")
        policy.windowLost()
        #expect(!policy.measured)
        #expect(policy.needsMeasure(isActive: true, category: "L"))
    }
}
