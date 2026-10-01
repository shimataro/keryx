import Testing

/// Covers `SidebarSwipeActions`: which trailing swipe actions each kind of sidebar row offers.
@Suite
struct SidebarSwipeActionsTests {
    @Test
    func aFeedCanBeUnsubscribedOrRenamedWhereverItIsShown() {
        #expect(SidebarSwipeActions.available(for: .feed("a")) == [.unsubscribe, .rename])
        #expect(SidebarSwipeActions.available(for: .feedInTag(feedId: "a", tagId: "t1")) == [.unsubscribe, .rename])
    }

    @Test
    func aFolderOrTagCanBeDeletedOrRenamed() {
        #expect(SidebarSwipeActions.available(for: .folder("d1")) == [.delete, .rename])
        #expect(SidebarSwipeActions.available(for: .tag("t1")) == [.delete, .rename])
    }

    @Test
    func rowsWithoutANameOrAnEntityOfferNothing() {
        #expect(SidebarSwipeActions.available(for: .all).isEmpty)
        #expect(SidebarSwipeActions.available(for: .starred).isEmpty)
        #expect(SidebarSwipeActions.available(for: .sectionHeader(.folders)).isEmpty)
        #expect(SidebarSwipeActions.available(for: .noFolderHeader).isEmpty)
    }
}
