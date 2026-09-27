import KeryxShared
import Observation

/// The sidebar's own dialog/sheet state (add feed, create/rename/delete folder or tag, unsubscribe
/// confirmation) — lifted out of `FeedListView` into a shared object so `HomeView`'s keyboard
/// handler (Return/Delete — see `HomeShortcuts.kt`'s `renameFeedListItem`/`deleteFeedListItem`) can
/// trigger the same dialogs the sidebar's own context menus open.
@MainActor
@Observable
final class SidebarDialogState {
    var isAddingFeed = false
    var isAddingFolder = false
    var isAddingTag = false
    var renamingFolder: Folders?
    var renamingTag: Tags?
    var renamingFeed: Feeds?
    var deletingFolder: Folders?
    var deletingTag: Tags?
    var unsubscribingFeed: Feeds?
}
