import KeryxShared
import Testing

/// Covers `SidebarRowContent`: what each iOS sidebar row displays, and which rows changed.
@Suite
struct SidebarRowContentTests {
    private typealias F = SidebarFixtures

    private func contents(
        feeds: [Feeds] = F.feeds,
        unreadByFeed: [String: Int64] = [:],
        selectionDisplayed: Bool = true,
        selectedRow: FeedListRowSelection = FeedListRowSelectionAll(),
        filter: ArticleFilter = ArticleFilterAll(),
        renamingRowKey: String? = nil
    ) -> [SidebarItemID: SidebarRowContent] {
        SidebarRowContent.build(
            outline: F.outline(feeds: feeds),
            model: F.model(feeds: feeds),
            unreadByFeed: unreadByFeed,
            unreadByFolder: ["d1": 7],
            unreadByTag: ["t1": 3],
            totalUnread: 10,
            starredUnreadCount: 2,
            selectionDisplayed: selectionDisplayed,
            selectedRow: selectedRow,
            filter: filter,
            renamingRowKey: renamingRowKey
        )
    }

    @Test
    func everyItemHasContent() {
        let all = contents()
        #expect(Set(all.keys) == Set(F.outline().allItems))
    }

    @Test
    func rowsShowTheirTitleIconAndUnreadCount() {
        let all = contents(unreadByFeed: ["a": 4])
        #expect(all[.all]?.unreadCount == 10)
        #expect(all[.starred]?.unreadCount == 2)
        #expect(all[.folder("d1")]?.title == "name-d1")
        #expect(all[.folder("d1")]?.icon == .symbol("folder"))
        #expect(all[.folder("d1")]?.unreadCount == 7)
        #expect(all[.tag("t1")]?.icon == .tagColor(hex: nil))
        #expect(all[.tag("t1")]?.unreadCount == 3)
        #expect(all[.feed("a")]?.icon == .favicon(url: "https://example.com/a.ico"))
        #expect(all[.feed("a")]?.unreadCount == 4)
        #expect(all[.feedInTag(feedId: "a", tagId: "t1")]?.unreadCount == 4)
        #expect(all[.noFolderHeader]?.icon == nil)
        #expect(all[.sectionHeader(.folders)]?.icon == nil)
    }

    @Test
    func aFeedShowsItsCustomTitleAndErrorState() {
        let feeds = [
            F.feed("a", sortOrder: 1, customTitle: "Mine"),
            F.feed("b", sortOrder: 2, errorCount: 2),
            F.feed("c", sortOrder: 3, lastError: ConstantsKt.FEED_ERROR_REASON_GONE),
        ]
        let all = contents(feeds: feeds)
        #expect(all[.feed("a")]?.title == "Mine")
        #expect(all[.feed("a")].map { !$0.isErroring && !$0.isGone } == true)
        #expect(all[.feed("b")].map { $0.isErroring && !$0.isGone } == true)
        #expect(all[.feed("c")].map { $0.isErroring && $0.isGone } == true)
    }

    @Test
    func otherCopiesOfTheSelectedFeedEcho() {
        let all = contents(
            selectedRow: FeedListRowSelectionFeedInFolderGroup(feedId: "a"),
            filter: ArticleFilterFeed(feedId: "a")
        )
        #expect(all[.feed("a")]?.highlight == SidebarRowHighlight.none)
        #expect(all[.feedInTag(feedId: "a", tagId: "t1")]?.highlight == .echo)
        #expect(all[.feed("b")]?.highlight == SidebarRowHighlight.none)
        #expect(all[.folder("d1")]?.highlight == SidebarRowHighlight.none)
    }

    @Test
    func nothingEchoesWhileTheSelectionIsNotDisplayed() {
        let all = contents(
            selectionDisplayed: false,
            selectedRow: FeedListRowSelectionFeedInFolderGroup(feedId: "a"),
            filter: ArticleFilterFeed(feedId: "a")
        )
        #expect(all.values.allSatisfy { $0.highlight == SidebarRowHighlight.none })
    }

    @Test
    func onlyTheRowBeingRenamedIsRenaming() {
        let all = contents(renamingRowKey: "feed-in-tag:t1:a")
        #expect(all.filter(\.value.isRenaming).map(\.key) == [.feedInTag(feedId: "a", tagId: "t1")])
    }

    @Test
    func changedItemsAreOnlyTheRowsWhoseContentDiffers() {
        let before = contents(unreadByFeed: ["a": 1, "b": 1])
        let after = contents(unreadByFeed: ["a": 2, "b": 1])
        #expect(SidebarRowContent.changedItems(from: before, to: after) == [.feed("a"), .feedInTag(feedId: "a", tagId: "t1")])
        #expect(SidebarRowContent.changedItems(from: before, to: before).isEmpty)
    }

    @Test
    func addedAndRemovedItemsAreNotChanges() {
        var before = contents()
        let after = contents()
        before.removeValue(forKey: .feed("a"))
        before[.folder("gone")] = before[.folder("d1")]
        #expect(SidebarRowContent.changedItems(from: before, to: after).isEmpty)
    }
}
