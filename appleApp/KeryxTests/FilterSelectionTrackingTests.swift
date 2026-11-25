import KeryxShared
import Testing

/// The decisions `HomeObservable` makes when a `FilterSelection` arrives and when the article list's
/// pull is judged finished, kept as pure values so they are tested without a view model.
@Suite
struct FilterSelectionTrackingTests {
    // MARK: assignFilterSelection

    @Test
    func aNewGenerationWithAnotherFilterIsASwitchThatAbandonsThePull() {
        var tracker = FilterSelectionTracker()
        let change = tracker.apply(generation: 1, filterChanged: true)
        #expect(change == .switched)
        #expect(change.abandonsPull)
        #expect(tracker.generation == 1)
    }

    @Test
    func aChangedFilterUnderTheSameGenerationIsAReKeyThatKeepsThePull() {
        var tracker = FilterSelectionTracker()
        _ = tracker.apply(generation: 3, filterChanged: true)
        let change = tracker.apply(generation: 3, filterChanged: true)
        #expect(change == .rekeyed)
        #expect(!change.abandonsPull)
    }

    @Test
    func anUnchangedFilterChangesNothingEvenUnderANewGeneration() {
        var tracker = FilterSelectionTracker()
        let change = tracker.apply(generation: 5, filterChanged: false)
        #expect(change == .unchanged)
        #expect(!change.abandonsPull)
        // The generation is still recorded, so the next delivery is judged against it.
        #expect(tracker.generation == 5)
        #expect(tracker.apply(generation: 5, filterChanged: true) == .rekeyed)
    }

    @Test
    func theGenerationStartsAtZeroLikeTheViewModels() {
        var tracker = FilterSelectionTracker()
        #expect(tracker.generation == 0)
        #expect(tracker.apply(generation: 0, filterChanged: true) == .rekeyed)
    }

    // MARK: awaitPull

    @Test
    func aPullIsPendingWhileItsFilterIsInTheSet() {
        let pending: [ArticleFilter] = [ArticleFilterTag(tagId: "t1"), ArticleFilterFeed(feedId: "f1")]
        #expect(PullMembership.isPending(ArticleFilterTag(tagId: "t1"), in: pending))
        #expect(PullMembership.isPending(ArticleFilterFeed(feedId: "f1"), in: pending))
    }

    @Test
    func aPullIsFinishedOnceItsFilterLeavesTheSet() {
        let pending: [ArticleFilter] = [ArticleFilterTag(tagId: "t1")]
        #expect(!PullMembership.isPending(ArticleFilterTag(tagId: "t2"), in: pending))
        #expect(!PullMembership.isPending(ArticleFilterFeed(feedId: "t1"), in: pending))
        #expect(!PullMembership.isPending(ArticleFilterAll(), in: [ArticleFilter]()))
    }

    /// The re-keyed pull: the set already holds the new id, so a current filter read as the old id
    /// would end the wait early — which is why the loop reads the ViewModel's current filter.
    @Test
    func aPullMovedOntoTheNewIdIsPendingForTheNewFilterOnly() {
        let pending: [ArticleFilter] = [ArticleFilterTag(tagId: "t-new")]
        #expect(PullMembership.isPending(ArticleFilterTag(tagId: "t-new"), in: pending))
        #expect(!PullMembership.isPending(ArticleFilterTag(tagId: "t-old"), in: pending))
    }
}
