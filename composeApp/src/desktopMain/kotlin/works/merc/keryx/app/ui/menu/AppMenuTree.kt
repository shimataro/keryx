package works.merc.keryx.app.ui.menu

import androidx.compose.ui.input.key.Key
import works.merc.keryx.app.platform.isMacOs

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
 * The modifier + key of a menu accelerator. Every shipped shortcut is a plain "mod" shortcut
 * (Ctrl elsewhere, ⌘ on macOS), so [ctrl] defaults to `true`; the in-window renderer applies the
 * platform modifier while the Linux [MenuShortcutDispatcher] matches [ctrl]/[meta] directly.
 */
internal enum class AppMenuShortcut(val key: Key, val ctrl: Boolean = true, val meta: Boolean = false) {
    AddFeed(Key.N),
    CloseWindow(Key.W),
    Settings(Key.Comma),
    Quit(Key.Q),
    RefreshAll(Key.R),
    ShowMenuBar(Key.M),
}

/** A node in the application menu tree. */
internal sealed interface AppMenuNode {
    data class Menu(val label: String, val items: List<AppMenuNode>) : AppMenuNode

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
    val openWebsite: () -> Unit,
    val openProjectPage: () -> Unit,
    val about: () -> Unit,
)

/**
 * Builds the application menu tree from the current [ui] state, resolved [labels] and [actions].
 *
 * The menu *shape* is fixed at startup: [isMacOs] is a process constant, and [menuBarToggle] only
 * ever adds/removes the trailing "Show Menu Bar" item. Everything else varies only by label /
 * enabled / checked, which is what keeps the D-Bus node ids stable across rebuilds
 * (see `AppMenuLayoutBuilder`).
 *
 * @param menuBarToggle when non-null, appends a "Show Menu Bar" checkbox to the View menu (KDE only).
 */
internal fun buildAppMenuTree(
    ui: MenuUiState,
    labels: AppMenuLabels,
    actions: AppMenuActions,
    menuBarToggle: MenuBarToggle?,
): AppMenuRoot {
    val fileItems = buildList {
        add(AppMenuNode.Item(labels.addFeed, ui.addItemsEnabled, AppMenuShortcut.AddFeed, actions.addFeed))
        add(AppMenuNode.Item(labels.addFolder, ui.addItemsEnabled, onClick = actions.addFolder))
        add(AppMenuNode.Item(labels.addTag, ui.addItemsEnabled, onClick = actions.addTag))
        add(AppMenuNode.Separator)
        add(AppMenuNode.Item(labels.importOpml, ui.opmlEnabled, onClick = actions.importOpml))
        add(AppMenuNode.Item(labels.exportOpml, ui.opmlEnabled, onClick = actions.exportOpml))
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
        add(AppMenuNode.Item(labels.search, ui.searchEnabled, onClick = actions.focusSearch))
        add(AppMenuNode.CheckboxItem(labels.unreadOnly, ui.searchEnabled, ui.unreadOnlyChecked, onCheckedChange = actions.setUnreadOnly))
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
        AppMenuNode.Item(labels.toggleRead, ui.articleActionsEnabled, onClick = actions.toggleRead),
        AppMenuNode.Item(labels.toggleStar, ui.articleActionsEnabled, onClick = actions.toggleStar),
        AppMenuNode.Separator,
        AppMenuNode.Item(labels.openInBrowser, ui.urlActionsEnabled, onClick = actions.openInBrowser),
        AppMenuNode.Item(labels.copyUrl, ui.urlActionsEnabled, onClick = actions.copyUrl),
    )

    val feedItems = listOf(
        AppMenuNode.Item(labels.refreshAll, ui.refreshAllEnabled, AppMenuShortcut.RefreshAll, actions.refreshAll),
        AppMenuNode.Item(labels.syncNow, ui.syncEnabled, onClick = actions.sync),
    )

    val helpItems = buildList {
        add(AppMenuNode.Item(labels.website, enabled = true, onClick = actions.openWebsite))
        add(AppMenuNode.Item(labels.projectPage, enabled = true, onClick = actions.openProjectPage))
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
