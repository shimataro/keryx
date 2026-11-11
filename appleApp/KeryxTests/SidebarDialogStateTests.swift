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

    /// `isPresenting` is what keeps `HomeView`'s key handler (and the Feed menu's bare Return/Delete)
    /// away from keys typed into a sheet's field — a bare K there must reach the field, not select
    /// the previous article — so every sheet/alert must turn it on, and closing it must turn it off.
    @Test
    func everySheetAndAlertCountsAsPresenting() {
        let feed = SidebarFixtures.feed("f1", sortOrder: 1)
        let opens: [(SidebarDialogState) -> Void] = [
            { $0.isAddingFeed = true },
            { $0.isAddingFolder = true },
            { $0.isAddingTag = true },
            { $0.creatingFolderForFeed = feed },
            { $0.creatingTagForFeed = feed },
            { $0.deletingFolder = SidebarFixtures.folder("d1", sortOrder: 1) },
            { $0.deletingTag = SidebarFixtures.tag("t1", sortOrder: 1) },
            { $0.unsubscribingFeed = feed },
        ]
        let closes: [(SidebarDialogState) -> Void] = [
            { $0.isAddingFeed = false },
            { $0.isAddingFolder = false },
            { $0.isAddingTag = false },
            { $0.creatingFolderForFeed = nil },
            { $0.creatingTagForFeed = nil },
            { $0.deletingFolder = nil },
            { $0.deletingTag = nil },
            { $0.unsubscribingFeed = nil },
        ]
        for (open, close) in zip(opens, closes) {
            let state = SidebarDialogState()
            #expect(!state.isPresenting)
            open(state)
            #expect(state.isPresenting)
            close(state)
            #expect(!state.isPresenting)
        }
    }

    /// `isEditingInline` is what stands the Feed menu's bare Return/Delete down while the editor is
    /// open (`HomeCommands.bareKeysActive`), so it must follow the edit exactly.
    @Test
    func isEditingInlineFollowsTheEdit() {
        let state = SidebarDialogState()
        #expect(!state.isEditingInline)
        state.startRename(FeedListRowSelectionStarred())
        #expect(!state.isEditingInline)
        state.startRename(FeedListRowSelectionFeedInTag(feedId: "f1", tagId: "t1"))
        #expect(state.isEditingInline)
        state.renamingRowKey = nil
        #expect(!state.isEditingInline)
    }
}
