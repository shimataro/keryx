package works.merc.keryx.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.platform.isMacOs
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.menu_article
import works.merc.keryx.app.resources.menu_article_copy_url
import works.merc.keryx.app.resources.menu_article_open_in_browser
import works.merc.keryx.app.resources.menu_article_toggle_read
import works.merc.keryx.app.resources.menu_article_toggle_star
import works.merc.keryx.app.resources.menu_feed
import works.merc.keryx.app.resources.menu_feed_refresh_all
import works.merc.keryx.app.resources.menu_feed_sync_now
import works.merc.keryx.app.resources.menu_file
import works.merc.keryx.app.resources.menu_file_add_feed
import works.merc.keryx.app.resources.menu_file_add_folder
import works.merc.keryx.app.resources.menu_file_add_tag
import works.merc.keryx.app.resources.menu_file_close_window
import works.merc.keryx.app.resources.menu_file_export_opml
import works.merc.keryx.app.resources.menu_file_import_opml
import works.merc.keryx.app.resources.menu_file_quit
import works.merc.keryx.app.resources.menu_help
import works.merc.keryx.app.resources.menu_help_about
import works.merc.keryx.app.resources.menu_help_project_page
import works.merc.keryx.app.resources.menu_help_website
import works.merc.keryx.app.resources.website_url
import works.merc.keryx.app.resources.menu_settings
import works.merc.keryx.app.resources.menu_view
import works.merc.keryx.app.resources.menu_view_mark_all_read
import works.merc.keryx.app.resources.menu_view_search
import works.merc.keryx.app.resources.menu_view_show_menu_bar
import works.merc.keryx.app.resources.menu_view_toggle_sort
import works.merc.keryx.app.resources.menu_view_unread_only
import works.merc.keryx.app.ui.home.HomeViewModel
import works.merc.keryx.app.ui.menu.AppMenuActions
import works.merc.keryx.app.ui.menu.AppMenuLabels
import works.merc.keryx.app.ui.menu.AppMenuNode
import works.merc.keryx.app.ui.menu.AppMenuRoot
import works.merc.keryx.app.ui.menu.AppMenuShortcut
import works.merc.keryx.app.ui.menu.MenuBarToggle
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController
import works.merc.keryx.app.ui.menu.buildAppMenuTree
import works.merc.keryx.app.ui.menu.computeMenuUiState
import works.merc.keryx.app.ui.settings.PROJECT_URL
import works.merc.keryx.app.ui.settings.SettingsViewModel

/**
 * Desktop application menu bar. On macOS this renders in the system (screen) menu bar; on
 * Windows/Linux it renders inside the window. Item enabled/checked state is derived by the pure
 * [computeMenuUiState]; clicks dispatch either to a ViewModel directly (state-carrying singletons)
 * or through [MenuController] for actions whose state lives inside a screen's composition.
 *
 * The whole menu is modelled once as an [AppMenuRoot] (see `AppMenuTree`) and then interpreted into
 * the Compose `MenuBar` DSL. The same tree is pushed to [onTreeChanged] on every recomposition so a
 * D-Bus exporter (KDE Global Menu, `AppMenuBarHost`) can mirror it — the two surfaces are guaranteed
 * to agree because they read the same model.
 *
 * On macOS, Settings, About and Quit are provided by the native app (Keryx) menu — via
 * `Desktop.setPreferencesHandler` / `setAboutHandler` and AWT's default Quit — so they are omitted
 * from this menu bar to avoid duplication.
 *
 * @param onCloseWindow hides the window to the tray (same as the window's close button).
 * @param onQuit terminates the application (used only off macOS).
 * @param menuBarToggle when non-null, adds a "Show Menu Bar" checkbox to the View menu (KDE only).
 * @param renderMenuBar when `false`, the tree is still computed and pushed to [onTreeChanged] but the
 *   in-window `MenuBar` is not rendered (the KDE Global Menu is showing it instead).
 * @param onTreeChanged invoked with the freshly built tree on every recomposition.
 */
/**
 * Builds the application menu and optionally renders it in the window menu bar.
 *
 * @param menuBarToggle Optional control for showing or hiding the menu bar.
 * @param renderMenuBar Whether to render the menu bar.
 * @param onTreeChanged Receives the current menu tree after composition.
 */
/**
 * Builds and optionally renders the application menu bar.
 *
 * Publishes the current menu tree through [onTreeChanged] on each recomposition. The menu bar
 * remains published when [renderMenuBar] is `false`.
 *
 * @param onCloseWindow Closes the current window.
 * @param onQuit Quits the application.
 * @param menuBarToggle Controls the menu bar visibility option.
 * @param renderMenuBar Whether to render the menu bar in the window.
 * @param onTreeChanged Receives the current menu tree.
 */
@Composable
internal fun FrameWindowScope.AppMenuBar(
    onCloseWindow: () -> Unit,
    onQuit: () -> Unit,
    menuBarToggle: MenuBarToggle? = null,
    renderMenuBar: Boolean = true,
    onTreeChanged: (AppMenuRoot) -> Unit = {},
) {
    val menuController = koinInject<MenuController>()
    val homeVm = koinInject<HomeViewModel>()
    val settingsVm = koinInject<SettingsViewModel>()

    val screen by menuController.currentScreen.collectAsState()
    val selected by homeVm.selectedArticle.collectAsState()
    val feedRefreshing by homeVm.feedRefreshing.collectAsState()
    val syncing by homeVm.syncing.collectAsState()
    val filter by homeVm.filter.collectAsState()
    val unreadOnly by homeVm.unreadOnly.collectAsState()
    val cloudConnected by homeVm.cloudConnected.collectAsState()

    val ui = computeMenuUiState(
        screen = screen,
        hasSelectedArticle = selected != null,
        selectedArticleHasUrl = selected?.url?.isNotBlank() == true,
        feedRefreshing = feedRefreshing,
        syncing = syncing,
        cloudConnected = cloudConnected,
        filterIsSearch = filter == ArticleFilter.Search,
        unreadOnly = unreadOnly,
    )

    val websiteUrl = stringResource(Res.string.website_url)
    val labels = AppMenuLabels(
        fileMenu = stringResource(Res.string.menu_file),
        addFeed = stringResource(Res.string.menu_file_add_feed),
        addFolder = stringResource(Res.string.menu_file_add_folder),
        addTag = stringResource(Res.string.menu_file_add_tag),
        importOpml = stringResource(Res.string.menu_file_import_opml),
        exportOpml = stringResource(Res.string.menu_file_export_opml),
        closeWindow = stringResource(Res.string.menu_file_close_window),
        settings = stringResource(Res.string.menu_settings),
        quit = stringResource(Res.string.menu_file_quit),
        viewMenu = stringResource(Res.string.menu_view),
        search = stringResource(Res.string.menu_view_search),
        unreadOnly = stringResource(Res.string.menu_view_unread_only),
        toggleSort = stringResource(Res.string.menu_view_toggle_sort),
        markAllRead = stringResource(Res.string.menu_view_mark_all_read),
        showMenuBar = stringResource(Res.string.menu_view_show_menu_bar),
        articleMenu = stringResource(Res.string.menu_article),
        toggleRead = stringResource(Res.string.menu_article_toggle_read),
        toggleStar = stringResource(Res.string.menu_article_toggle_star),
        openInBrowser = stringResource(Res.string.menu_article_open_in_browser),
        copyUrl = stringResource(Res.string.menu_article_copy_url),
        feedMenu = stringResource(Res.string.menu_feed),
        refreshAll = stringResource(Res.string.menu_feed_refresh_all),
        syncNow = stringResource(Res.string.menu_feed_sync_now),
        helpMenu = stringResource(Res.string.menu_help),
        website = stringResource(Res.string.menu_help_website),
        projectPage = stringResource(Res.string.menu_help_project_page),
        about = stringResource(Res.string.menu_help_about),
    )

    val actions = AppMenuActions(
        addFeed = { menuController.send(MenuCommand.AddFeed) },
        addFolder = { menuController.send(MenuCommand.AddFolder) },
        addTag = { menuController.send(MenuCommand.AddTag) },
        importOpml = { settingsVm.importOpml() },
        exportOpml = { settingsVm.exportOpml() },
        closeWindow = onCloseWindow,
        openSettings = { menuController.send(MenuCommand.OpenSettings) },
        quit = onQuit,
        focusSearch = { menuController.send(MenuCommand.FocusSearch) },
        setUnreadOnly = { homeVm.setUnreadOnly(it) },
        toggleSort = { homeVm.toggleSort() },
        markAllRead = { homeVm.markAllRead() },
        toggleRead = { homeVm.toggleReadSelected() },
        toggleStar = { homeVm.toggleStarSelected() },
        openInBrowser = { menuController.send(MenuCommand.OpenInBrowser) },
        copyUrl = { menuController.send(MenuCommand.CopyUrl) },
        refreshAll = { homeVm.refreshAll() },
        sync = { homeVm.sync() },
        openWebsite = { BrowserOpener.open(websiteUrl) },
        openProjectPage = { BrowserOpener.open(PROJECT_URL) },
        about = { menuController.send(MenuCommand.About) },
    )

    val tree = buildAppMenuTree(ui, labels, actions, menuBarToggle)

    // Publish the tree to any D-Bus exporter on every (re)composition; harmless (a no-op default)
    // when there is no registrar.
    SideEffect { onTreeChanged(tree) }

    if (renderMenuBar) {
        MenuBar {
            tree.menus.forEach { menu ->
                Menu(menu.label) { renderNodes(menu.items) }
            }
        }
    }
}

/** Interprets [AppMenuNode]s into the Compose `MenuBar` DSL. Recurses through nested submenus. */
@Composable
private fun MenuScope.renderNodes(nodes: List<AppMenuNode>) {
    nodes.forEach { node ->
        when (node) {
            is AppMenuNode.Menu -> Menu(node.label) { renderNodes(node.items) }
            is AppMenuNode.Item -> Item(
                text = node.label,
                shortcut = node.shortcut?.toKeyShortcut(),
                enabled = node.enabled,
                onClick = node.onClick,
            )
            is AppMenuNode.CheckboxItem -> CheckboxItem(
                text = node.label,
                checked = node.checked,
                shortcut = node.shortcut?.toKeyShortcut(),
                enabled = node.enabled,
                onCheckedChange = node.onCheckedChange,
            )
            AppMenuNode.Separator -> Separator()
        }
    }
}

/** ⌘ on macOS, Ctrl elsewhere — the platform "mod" every shipped menu accelerator uses. */
private fun AppMenuShortcut.toKeyShortcut(): KeyShortcut = KeyShortcut(key, meta = isMacOs, ctrl = !isMacOs)
