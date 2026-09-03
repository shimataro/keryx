package works.merc.keryx.app.ui.menu

import androidx.compose.ui.input.key.Key
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.platform.isMacOs
import works.merc.keryx.app.tray.TrayUpdateEntry

/**
 * The desktop application menu, modelled as a single tree that is built once per composition and
 * consumed by **both** the in-window Compose `MenuBar` renderer (`AppMenuBar`) and — on Linux with a
 * KDE Global Menu registrar — the `com.canonical.dbusmenu` D-Bus layout builder (`AppMenuLayoutBuilder`).
 * Building both surfaces from one model means they can never drift out of sync (see the plan's
 * Decision 3).
 *
 * The model carries pre-resolved [String] labels and plain action lambdas: `stringResource` only
 * resolves inside `@Composable` scope, so `AppMenuBar` resolves the labels once and passes them down,
 * mirroring how `TrayMenuState` already works for the tray.
 */

/**
 * The modifier + key of a menu accelerator. [ctrl] means "use the platform's primary modifier"
 * (Ctrl elsewhere, ⌘ on macOS) — the in-window renderer (`AppMenuBar.toKeyShortcut`) derives the
 * actual per-platform `ctrl`/`meta` `KeyShortcut` flags from it, and the Linux
 * [MenuShortcutDispatcher] matches [ctrl]/[meta] directly. It defaults to `true`, used by every
 * "always available" shortcut as a plain Ctrl/⌘ combo. Selected-item shortcuts (enabled only with
 * the right selection/focus — the Article and Feed menus' `Ctrl+Shift+<letter>` entries)
 * additionally set [shift], keeping them in a chord space that can never collide with a plain-Ctrl
 * "always available" shortcut and is never typed into a text field (unlike the bare, unmodified
 * keys `KeyboardNav.kt` and the context menus use). `Rename`/`Unsubscribe` are the deliberate
 * exception — [ctrl] is `false`, so they keep their original bare accelerator (F2/Return, Delete),
 * since a bare "act on the focused/selected item" key is itself an established convention
 * (file-manager rename/delete); see [MenuUiState.renameOrDeleteEnabled]'s `textInputFocused`
 * guard for how that stays safe.
 *
 * [dbusmenuKeyName] is the AWT virtual-key *name* the `com.canonical.dbusmenu` host expects for
 * this key — plain strings, so it lives here alongside [key] rather than in `appmenu/`. The AWT
 * virtual-key *code* ([java.awt.event.KeyEvent] `VK_*`) is deliberately **not** a property here —
 * see `appmenu/MenuBarVisibility.kt`'s `awtKeyCode()` — so this model stays AWT-free.
 */
internal enum class AppMenuShortcut(
    val key: Key,
    val dbusmenuKeyName: String,
    val ctrl: Boolean = true,
    val meta: Boolean = false,
    val shift: Boolean = false,
) {
    AddFeed(Key.N, "N"),
    CloseWindow(Key.W, "W"),
    Settings(Key.Comma, ","),
    Quit(Key.Q, "Q"),
    RefreshAll(Key.R, "R"),
    ShowMenuBar(Key.M, "M"),
    Search(Key.F, "F"),
    ImportOpml(Key.I, "I"),
    ExportOpml(Key.E, "E"),
    UnreadOnly(Key.U, "U"),
    ToggleRead(Key.U, "U", shift = true),
    ToggleStar(Key.S, "S", shift = true),
    OpenInBrowser(Key.O, "O", shift = true),
    CopyUrl(Key.C, "C", shift = true),
    FeedRefresh(Key.R, "R", shift = true),
    FeedRename(if (isMacOs) Key.Enter else Key.F2, if (isMacOs) "Return" else "F2", ctrl = false),
    FeedUnsubscribe(Key.Delete, "Delete", ctrl = false),
}

/** A node in the application menu tree. */
internal sealed interface AppMenuNode {
    data class Menu(val label: String, val items: List<AppMenuNode>, val enabled: Boolean = true) : AppMenuNode

    data class Item(
        val label: String,
        val enabled: Boolean,
        val shortcut: AppMenuShortcut? = null,
        val onClick: () -> Unit,
    ) : AppMenuNode

    data class CheckboxItem(
        val label: String,
        val enabled: Boolean,
        val checked: Boolean,
        val shortcut: AppMenuShortcut? = null,
        val onCheckedChange: (Boolean) -> Unit,
    ) : AppMenuNode

    data object Separator : AppMenuNode
}

/** The whole menu: an ordered list of top-level menus. */
internal data class AppMenuRoot(val menus: List<AppMenuNode.Menu>)

/**
 * The optional "Show Menu Bar" toggle appended to the View menu. Non-null only when a Global Menu
 * registrar connection exists (Linux/KDE); [visible] is the current in-window bar visibility and
 * [onToggle] flips + persists it.
 */
internal data class MenuBarToggle(val visible: Boolean, val onToggle: (Boolean) -> Unit)

/** Pre-resolved labels for every menu item (resolved once in `AppMenuBar` via `stringResource`). */
internal data class AppMenuLabels(
    val fileMenu: String,
    val addFeed: String,
    val addFolder: String,
    val addTag: String,
    val importOpml: String,
    val exportOpml: String,
    val closeWindow: String,
    val settings: String,
    val quit: String,
    val viewMenu: String,
    val search: String,
    val unreadOnly: String,
    val toggleSort: String,
    val markAllRead: String,
    val showMenuBar: String,
    val articleMenu: String,
    val toggleRead: String,
    val toggleStar: String,
    val openInBrowser: String,
    val copyUrl: String,
    val feedMenu: String,
    val refreshAll: String,
    val syncNow: String,
    val feedRefresh: String,
    val feedAssignTags: String,
    val feedMoveToFolder: String,
    val feedNoFolder: String,
    /** Rename/delete wording for the *currently selected* item — a feed, folder or tag, not always
     * a feed (`AppMenuBar` picks the matching string per selection type). */
    val feedRename: String,
    val feedUnsubscribe: String,
    val feedCopyUrl: String,
    val feedCopySiteUrl: String,
    val feedOpenSite: String,
    val helpMenu: String,
    val website: String,
    val projectPage: String,
    val about: String,
)

/** The action lambdas backing each menu item, built once in `AppMenuBar`. */
internal data class AppMenuActions(
    val addFeed: () -> Unit,
    val addFolder: () -> Unit,
    val addTag: () -> Unit,
    val importOpml: () -> Unit,
    val exportOpml: () -> Unit,
    val closeWindow: () -> Unit,
    val openSettings: () -> Unit,
    val quit: () -> Unit,
    val focusSearch: () -> Unit,
    val setUnreadOnly: (Boolean) -> Unit,
    val toggleSort: () -> Unit,
    val markAllRead: () -> Unit,
    val toggleRead: () -> Unit,
    val toggleStar: () -> Unit,
    val openInBrowser: () -> Unit,
    val copyUrl: () -> Unit,
    val refreshAll: () -> Unit,
    val sync: () -> Unit,
    val refreshSelectedFeed: () -> Unit,
    val toggleFeedTag: (tagId: String, attached: Boolean) -> Unit,
    val moveFeedToFolder: (folderId: String?) -> Unit,
    val renameSelectedFeed: () -> Unit,
    val unsubscribeSelectedFeed: () -> Unit,
    val copyFeedUrl: () -> Unit,
    val copyFeedSiteUrl: () -> Unit,
    val openFeedSite: () -> Unit,
    val openWebsite: () -> Unit,
    val openProjectPage: () -> Unit,
    /** Runs whatever the Help menu's single update entry currently stands for — the same
     * `onUpdateMenuItemClicked` the tray's own entry calls. */
    val updateAction: () -> Unit,
    val about: () -> Unit,
)

/**
 * The selected feed's dynamic submenu data for the Feed menu's Tags/Move-to-folder items. Empty
 * lists / a `null` [currentFolderId] when no feed is meaningfully selected — harmless, since the
 * submenus are disabled via [MenuUiState.feedActionsEnabled] in that case and never opened.
 */
internal data class SelectedFeedMenuData(
    val tags: List<Tags>,
    val attachedTagIds: Set<String>,
    val folders: List<Folders>,
    val currentFolderId: String?,
)

/**
 * Builds the application menu tree from the current [ui] state, resolved [labels] and [actions].
 *
 * The menu shape is fixed at startup **except** for the Feed menu's Tags/Move-to-folder submenus,
 * whose item count follows [selectedFeedMenu]'s tag/folder lists and can therefore change while the
 * app is running: [isMacOs] is a process constant, and [menuBarToggle] only ever adds/removes the
 * trailing "Show Menu Bar" item. Everything else (including the rest of this tree) varies only by
 * label / enabled / checked, which is what keeps the D-Bus node ids stable across rebuilds for that
 * fixed portion (see `AppMenuLayoutBuilder`, which documents why the variable-length region is
 * still safe).
 *
 * @param menuBarToggle when non-null, appends a "Show Menu Bar" checkbox to the View menu (KDE only).
 * @param selectedFeedMenu the currently selected feed's tags/folder data, backing the Feed menu's
 *   Tags/Move-to-folder submenus (empty/`null` content when [MenuUiState.feedActionsEnabled] is
 *   `false` — see [SelectedFeedMenuData]).
 * @param updateEntry the Help menu's in-app update entry — the very same label/enabled pair the
 *   system tray shows, resolved by `tray/UpdateMenuEntry.kt`'s `updateMenuEntry` so the two menus
 *   can never disagree. Passed directly rather than through [MenuUiState]/[AppMenuLabels] (like
 *   [menuBarToggle] and [selectedFeedMenu] before it): those are `commonMain` types, and
 *   `UpdateState`'s desktop-only tray mapping has no business in them. Its item is always present,
 *   disabled in the states with nothing to act on.
 */
internal fun buildAppMenuTree(
    ui: MenuUiState,
    labels: AppMenuLabels,
    actions: AppMenuActions,
    menuBarToggle: MenuBarToggle?,
    selectedFeedMenu: SelectedFeedMenuData,
    updateEntry: TrayUpdateEntry,
): AppMenuRoot {
    val fileItems = buildList {
        add(AppMenuNode.Item(labels.addFeed, ui.addItemsEnabled, AppMenuShortcut.AddFeed, actions.addFeed))
        add(AppMenuNode.Item(labels.addFolder, ui.addItemsEnabled, onClick = actions.addFolder))
        add(AppMenuNode.Item(labels.addTag, ui.addItemsEnabled, onClick = actions.addTag))
        add(AppMenuNode.Separator)
        add(AppMenuNode.Item(labels.importOpml, ui.opmlEnabled, AppMenuShortcut.ImportOpml, actions.importOpml))
        add(AppMenuNode.Item(labels.exportOpml, ui.opmlEnabled, AppMenuShortcut.ExportOpml, actions.exportOpml))
        add(AppMenuNode.Separator)
        add(AppMenuNode.Item(labels.closeWindow, enabled = true, shortcut = AppMenuShortcut.CloseWindow, onClick = actions.closeWindow))
        // On macOS, Settings and Quit live in the native app menu (see main.kt).
        if (!isMacOs) {
            add(AppMenuNode.Separator)
            add(AppMenuNode.Item(labels.settings, ui.openSettingsEnabled, AppMenuShortcut.Settings, actions.openSettings))
            add(AppMenuNode.Item(labels.quit, enabled = true, shortcut = AppMenuShortcut.Quit, onClick = actions.quit))
        }
    }

    val viewItems = buildList {
        add(AppMenuNode.Item(labels.search, ui.searchEnabled, AppMenuShortcut.Search, actions.focusSearch))
        add(
            AppMenuNode.CheckboxItem(
                labels.unreadOnly,
                ui.unreadOnlyEnabled,
                ui.unreadOnlyChecked,
                AppMenuShortcut.UnreadOnly,
                onCheckedChange = actions.setUnreadOnly,
            ),
        )
        add(AppMenuNode.Item(labels.toggleSort, ui.toggleSortEnabled, onClick = actions.toggleSort))
        add(AppMenuNode.Separator)
        add(AppMenuNode.Item(labels.markAllRead, ui.markAllReadEnabled, onClick = actions.markAllRead))
        // The Global-Menu discoverability toggle: only present when a registrar connection exists.
        if (menuBarToggle != null) {
            add(
                AppMenuNode.CheckboxItem(
                    label = labels.showMenuBar,
                    enabled = true,
                    checked = menuBarToggle.visible,
                    shortcut = AppMenuShortcut.ShowMenuBar,
                    onCheckedChange = menuBarToggle.onToggle,
                ),
            )
        }
    }

    val articleItems = listOf(
        AppMenuNode.Item(labels.toggleRead, ui.articleActionsEnabled, AppMenuShortcut.ToggleRead, actions.toggleRead),
        AppMenuNode.Item(labels.toggleStar, ui.articleActionsEnabled, AppMenuShortcut.ToggleStar, actions.toggleStar),
        AppMenuNode.Separator,
        AppMenuNode.Item(labels.openInBrowser, ui.urlActionsEnabled, AppMenuShortcut.OpenInBrowser, actions.openInBrowser),
        AppMenuNode.Item(labels.copyUrl, ui.urlActionsEnabled, AppMenuShortcut.CopyUrl, actions.copyUrl),
    )

    val feedItems = listOf(
        AppMenuNode.Item(labels.refreshAll, ui.refreshAllEnabled, AppMenuShortcut.RefreshAll, actions.refreshAll),
        AppMenuNode.Item(labels.syncNow, ui.syncEnabled, onClick = actions.sync),
        AppMenuNode.Separator,
        AppMenuNode.Item(labels.feedRefresh, ui.feedActionsEnabled, AppMenuShortcut.FeedRefresh, actions.refreshSelectedFeed),
        AppMenuNode.Menu(
            label = labels.feedAssignTags,
            enabled = ui.feedActionsEnabled,
            items = selectedFeedMenu.tags.map { tag ->
                AppMenuNode.CheckboxItem(
                    label = tag.name,
                    enabled = true,
                    checked = tag.id in selectedFeedMenu.attachedTagIds,
                    onCheckedChange = { attached -> actions.toggleFeedTag(tag.id, attached) },
                )
            },
        ),
        AppMenuNode.Menu(
            label = labels.feedMoveToFolder,
            enabled = ui.feedActionsEnabled,
            items = buildList {
                add(
                    AppMenuNode.CheckboxItem(
                        label = labels.feedNoFolder,
                        enabled = true,
                        checked = selectedFeedMenu.currentFolderId == null,
                        onCheckedChange = { actions.moveFeedToFolder(null) },
                    ),
                )
                selectedFeedMenu.folders.forEach { folder ->
                    add(
                        AppMenuNode.CheckboxItem(
                            label = folder.name,
                            enabled = true,
                            checked = selectedFeedMenu.currentFolderId == folder.id,
                            onCheckedChange = { actions.moveFeedToFolder(folder.id) },
                        ),
                    )
                }
            },
        ),
        AppMenuNode.Separator,
        AppMenuNode.Item(labels.feedCopyUrl, ui.feedActionsEnabled, onClick = actions.copyFeedUrl),
        AppMenuNode.Item(labels.feedCopySiteUrl, ui.feedSiteUrlActionsEnabled, onClick = actions.copyFeedSiteUrl),
        AppMenuNode.Item(labels.feedOpenSite, ui.feedSiteUrlActionsEnabled, onClick = actions.openFeedSite),
        // Rename/Unsubscribe act on whatever feed list item is selected (feed, folder or tag), so
        // unlike the items above they use renameOrDeleteEnabled, not feedActionsEnabled.
        AppMenuNode.Separator,
        AppMenuNode.Item(labels.feedRename, ui.renameOrDeleteEnabled, AppMenuShortcut.FeedRename, actions.renameSelectedFeed),
        AppMenuNode.Separator,
        AppMenuNode.Item(labels.feedUnsubscribe, ui.renameOrDeleteEnabled, AppMenuShortcut.FeedUnsubscribe, actions.unsubscribeSelectedFeed),
    )

    val helpItems = buildList {
        add(AppMenuNode.Item(labels.website, enabled = true, onClick = actions.openWebsite))
        add(AppMenuNode.Item(labels.projectPage, enabled = true, onClick = actions.openProjectPage))
        // The in-app update entry, separated from the external links above it.
        add(AppMenuNode.Separator)
        add(AppMenuNode.Item(updateEntry.label, updateEntry.enabled, onClick = actions.updateAction))
        // On macOS, About lives in the native app menu (see main.kt).
        if (!isMacOs) {
            add(AppMenuNode.Item(labels.about, enabled = true, onClick = actions.about))
        }
    }

    return AppMenuRoot(
        menus = listOf(
            AppMenuNode.Menu(labels.fileMenu, fileItems),
            AppMenuNode.Menu(labels.viewMenu, viewItems),
            AppMenuNode.Menu(labels.articleMenu, articleItems),
            AppMenuNode.Menu(labels.feedMenu, feedItems),
            AppMenuNode.Menu(labels.helpMenu, helpItems),
        ),
    )
}
