import Testing

/// `ArticleTableRevealTracker` decides when the macOS table scrolls the selected row into view: when
/// the user selects, and for a selection restored at launch — never because the selection's id moved
/// on its own.
@Suite
struct ArticleTableRevealTrackerTests {
    @Test
    func aSelectionRestoredAtLaunchIsRevealedOnceTheTableIsLaidOut() {
        var tracker = ArticleTableRevealTracker(revealRequest: 0)

        #expect(tracker.update(selectedId: "a1", revealRequest: 0) == .afterLayout)
    }

    @Test
    func noSelectionAtLaunchRevealsNothing() {
        var tracker = ArticleTableRevealTracker(revealRequest: 0)

        #expect(tracker.update(selectedId: nil, revealRequest: 0) == .none)
    }

    @Test
    func aSelectionTheUserMakesIsRevealedAtOnce() {
        var tracker = ArticleTableRevealTracker(revealRequest: 3)
        _ = tracker.update(selectedId: "a1", revealRequest: 3)

        #expect(tracker.update(selectedId: "a2", revealRequest: 4) == .now)
    }

    /// The selection handed back after a tombstone: the id changes, the request does not.
    @Test
    func theSelectionsIdMovingOnItsOwnScrollsNothing() {
        var tracker = ArticleTableRevealTracker(revealRequest: 3)
        _ = tracker.update(selectedId: "a1", revealRequest: 3)
        _ = tracker.update(selectedId: "a2", revealRequest: 4)

        #expect(tracker.update(selectedId: "a1", revealRequest: 4) == .none)
    }

    @Test
    func anUpdateThatChangesNothingScrollsNothing() {
        var tracker = ArticleTableRevealTracker(revealRequest: 0)
        _ = tracker.update(selectedId: "a1", revealRequest: 0)

        #expect(tracker.update(selectedId: "a1", revealRequest: 0) == .none)
    }

    /// A user's selection arriving with the very first update waits for the layout rather than
    /// revealing before the table has a size.
    @Test
    func aRequestOnTheFirstUpdateWaitsForTheLayout() {
        var tracker = ArticleTableRevealTracker(revealRequest: 0)

        #expect(tracker.update(selectedId: "a1", revealRequest: 1) == .afterLayout)
    }

    @Test
    func theRequestTheTableIsCreatedUnderIsNotARequest() {
        var tracker = ArticleTableRevealTracker(revealRequest: 7)
        _ = tracker.update(selectedId: "a1", revealRequest: 7)

        #expect(tracker.update(selectedId: "a1", revealRequest: 7) == .none)
    }
}
