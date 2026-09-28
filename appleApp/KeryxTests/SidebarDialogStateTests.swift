import KeryxShared
import Testing

/// `SidebarDialogState.startRename` decides which sidebar rows turn into an in-place name editor,
/// and keys the edit by the *rendered copy* of the row — the same feed under its folder and under
/// an expanded tag must be two different edits.
@MainActor
@Suite
struct SidebarDialogStateTests {
    @Test
    func allAndStarredHaveNoNameToEdit() {
        let state = SidebarDialogState()
        state.startRename(FeedListRowSelectionAll())
        #expect(state.renamingRowKey == nil)
        state.startRename(FeedListRowSelectionStarred())
        #expect(state.renamingRowKey == nil)
    }

    @Test
    func folderTagAndFeedRowsStartAnEditKeyedByTheirRow() {
        let state = SidebarDialogState()
        let rows: [FeedListRowSelection] = [
            FeedListRowSelectionFolder(folderId: "d1"),
            FeedListRowSelectionTag(tagId: "t1"),
            FeedListRowSelectionFeedInFolderGroup(feedId: "f1"),
            FeedListRowSelectionFeedInTag(feedId: "f1", tagId: "t1"),
        ]
        for row in rows {
            state.renamingRowKey = nil
            state.startRename(row)
            #expect(state.renamingRowKey == feedListRowSelectionKey(row))
        }
    }

    @Test
    func theSameFeedInFolderAndInTagIsEditedIndependently() {
        let inFolder = FeedListRowSelectionFeedInFolderGroup(feedId: "f1")
        let inTag = FeedListRowSelectionFeedInTag(feedId: "f1", tagId: "t1")
        #expect(feedListRowSelectionKey(inFolder) != feedListRowSelectionKey(inTag))
    }

    @Test
    func anInlineEditIsNotASheet() {
        let state = SidebarDialogState()
        state.startRename(FeedListRowSelectionFolder(folderId: "d1"))
        #expect(!state.isPresenting)
    }
}
