import KeryxShared
import Observation

/// The sidebar's own dialog/sheet state (add feed, create/delete folder or tag, unsubscribe
/// confirmation) — lifted out of `FeedListView` into a shared object so `HomeView`'s keyboard
/// handler (Return/Delete — see `HomeShortcuts.kt`'s `renameFeedListItem`/`deleteFeedListItem`) can
/// trigger the same dialogs the sidebar's own context menus open.
@MainActor
@Observable
final class SidebarDialogState {
    var isAddingFeed = false
    var isAddingFolder = false
    var isAddingTag = false
    /// The `feedListRowSelectionKey` of the one row whose name is being edited in place — a key
    /// rather than a folder/tag/feed, since the same feed can be rendered under its folder and under
    /// an expanded tag and only the row that was asked for turns into an editor (Compose's own
    /// `InlineEditTarget.Feed(feedId, tagId)`). Not a sheet, so `isPresenting` ignores it: an open
    /// editor holds real focus (`HomeFocusedPane.rowNameEditor`), which is what stands the bare
    /// Return/Delete accelerators down.
    var renamingRowKey: String?
    var deletingFolder: Folders?
    var deletingTag: Tags?
    var unsubscribingFeed: Feeds?
    /// The feed a feed-row's "Move to Folder ▸ New folder…" menu item was chosen for — the created
    /// folder is assigned to this feed on confirm (`FeedListDialogs.kt`'s own
    /// `creatingFolderForFeedId`).
    var creatingFolderForFeed: Feeds?
    /// The feed a feed-row's "Assign tags ▸ New tag…" menu item was chosen for — the created tag is
    /// attached to this feed on confirm (`FeedListDialogs.kt`'s own `creatingTagForFeedId`).
    var creatingTagForFeed: Feeds?

    /// Starts editing `instance`'s name in place. All and Starred have no name to edit, so they do
    /// nothing.
    func startRename(_ instance: FeedListRowSelection) {
        switch onEnum(of: instance) {
        case .all, .starred: return
        case .folder, .tag, .feedInFolderGroup, .feedInTag: renamingRowKey = feedListRowSelectionKey(instance)
        }
    }

    /// Whether any of the above sheets/alerts is currently on screen — gates the Feed menu's bare
    /// Return/Delete accelerators (`HomeCommands.bareKeysActive`) so, say, Backspace inside
    /// `NamePromptSheet`'s text field edits the field instead of also triggering the sidebar's own
    /// unsubscribe confirmation underneath it.
    var isPresenting: Bool {
        isAddingFeed || isAddingFolder || isAddingTag
            || deletingFolder != nil || deletingTag != nil || unsubscribingFeed != nil
            || creatingFolderForFeed != nil || creatingTagForFeed != nil
    }
}
