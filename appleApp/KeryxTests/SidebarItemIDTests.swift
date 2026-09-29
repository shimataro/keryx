import KeryxShared
import Testing

/// `SidebarItemID` is the iOS sidebar's `Sendable` item identity; its selection key must be the
/// one the rest of the app keys rows by (`feedListRowSelectionKey`).
@Suite
struct SidebarItemIDTests {
    private let selections: [FeedListRowSelection] = [
        FeedListRowSelectionAll(),
        FeedListRowSelectionStarred(),
        FeedListRowSelectionFolder(folderId: "d1"),
        FeedListRowSelectionTag(tagId: "t1"),
        FeedListRowSelectionFeedInFolderGroup(feedId: "f1"),
        FeedListRowSelectionFeedInTag(feedId: "f1", tagId: "t1"),
    ]

    @Test
    func selectionKeyMatchesFeedListRowSelectionKey() {
        for selection in selections {
            #expect(SidebarItemID(selection).selectionKey == feedListRowSelectionKey(selection))
        }
    }

    @Test
    func rowSelectionRoundTrips() {
        for selection in selections {
            let item = SidebarItemID(selection)
            #expect(item.rowSelection.map { feedListRowSelectionsEqual($0, selection) } == true)
            #expect(item.rowSelection.map(SidebarItemID.init) == item)
        }
    }

    @Test
    func headersAreNeverSelected() {
        for header in [SidebarItemID.sectionHeader(.folders), .sectionHeader(.tags), .noFolderHeader] {
            #expect(header.selectionKey == nil)
            #expect(header.rowSelection == nil)
        }
    }

    @Test
    func onlyFeedsAndFoldersAreDragged() {
        #expect(SidebarItemID.feed("f1").dragPayload == FeedListDragPayload(kind: .feed, id: "f1"))
        #expect(SidebarItemID.feedInTag(feedId: "f1", tagId: "t1").dragPayload == FeedListDragPayload(kind: .feed, id: "f1"))
        #expect(SidebarItemID.folder("d1").dragPayload == FeedListDragPayload(kind: .folder, id: "d1"))
        for item in [SidebarItemID.all, .starred, .tag("t1"), .sectionHeader(.folders), .noFolderHeader] {
            #expect(item.dragPayload == nil)
        }
    }
}
