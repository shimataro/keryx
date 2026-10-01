import Testing

/// Covers `SidebarUnreadCounts` and `SidebarItemID.unreadSource`: which count each iOS sidebar row
/// shows, and which rows a count change reconfigures.
@Suite
struct SidebarUnreadCountsTests {
    private typealias F = SidebarFixtures

    private let counts = SidebarUnreadCounts(
        byFeed: ["a": 4],
        byFolder: ["d1": 7],
        byTag: ["t1": 3],
        total: 10,
        starred: 2
    )

    @Test
    func eachItemMapsToItsUnreadSource() {
        #expect(SidebarItemID.all.unreadSource == .total)
        #expect(SidebarItemID.starred.unreadSource == .starred)
        #expect(SidebarItemID.folder("d1").unreadSource == .folder("d1"))
        #expect(SidebarItemID.tag("t1").unreadSource == .tag("t1"))
        #expect(SidebarItemID.feed("a").unreadSource == .feed("a"))
        #expect(SidebarItemID.feedInTag(feedId: "a", tagId: "t1").unreadSource == .feed("a"))
    }

    @Test
    func headersHaveNoUnreadSource() {
        #expect(SidebarItemID.sectionHeader(.folders).unreadSource == nil)
        #expect(SidebarItemID.sectionHeader(.tags).unreadSource == nil)
        #expect(SidebarItemID.noFolderHeader.unreadSource == nil)
    }

    @Test
    func rowsShowTheirCount() {
        #expect(counts.count(for: SidebarItemID.all) == 10)
        #expect(counts.count(for: SidebarItemID.starred) == 2)
        #expect(counts.count(for: SidebarItemID.folder("d1")) == 7)
        #expect(counts.count(for: SidebarItemID.tag("t1")) == 3)
        #expect(counts.count(for: SidebarItemID.feed("a")) == 4)
        #expect(counts.count(for: SidebarItemID.feedInTag(feedId: "a", tagId: "t1")) == 4)
    }

    @Test
    func missingCountsAndHeadersShowZero() {
        #expect(counts.count(for: SidebarItemID.feed("b")) == 0)
        #expect(counts.count(for: SidebarItemID.folder("d2")) == 0)
        #expect(counts.count(for: SidebarItemID.tag("t2")) == 0)
        #expect(counts.count(for: SidebarItemID.sectionHeader(.folders)) == 0)
        #expect(counts.count(for: SidebarItemID.noFolderHeader) == 0)
    }

    @Test
    func aFeedCountChangeReconfiguresBothOfItsCopies() {
        var after = counts
        after.byFeed["a"] = 3
        after.byFolder["d1"] = 6
        after.byTag["t1"] = 2
        after.total = 9
        let changed = SidebarUnreadCounts.changedItems(F.outline().allItems, from: counts, to: after)
        #expect(Set(changed) == [.all, .folder("d1"), .tag("t1"), .feed("a"), .feedInTag(feedId: "a", tagId: "t1")])
    }

    @Test
    func onlyItemsWhoseCountChangedAreReturned() {
        var after = counts
        after.starred = 1
        after.byFeed["b"] = 0 // Absent and 0 show the same count.
        let changed = SidebarUnreadCounts.changedItems(F.outline().allItems, from: counts, to: after)
        #expect(changed == [.starred])
    }

    @Test
    func unchangedCountsReconfigureNothing() {
        #expect(SidebarUnreadCounts.changedItems(F.outline().allItems, from: counts, to: counts).isEmpty)
    }

    @Test
    func itemsNotInTheListAreNotReturned() {
        var after = counts
        after.byFeed["a"] = 0
        after.byTag["t2"] = 5
        // Tag t1 collapsed: its copy of feed a is not in the list; t2 has no rows of its own.
        let items: [SidebarItemID] = [.all, .feed("a"), .tag("t1")]
        #expect(SidebarUnreadCounts.changedItems(items, from: counts, to: after) == [.feed("a")])
    }
}
