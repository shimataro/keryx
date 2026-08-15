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
import works.merc.keryx.app.resources.home_assign_tags
import works.merc.keryx.app.resources.home_copy_feed_url
import works.merc.keryx.app.resources.home_copy_site_url
import works.merc.keryx.app.resources.home_menu_delete_folder
import works.merc.keryx.app.resources.home_menu_delete_tag
import works.merc.keryx.app.resources.home_menu_rename_folder
import works.merc.keryx.app.resources.home_menu_rename_tag
import works.merc.keryx.app.resources.home_move_to_folder
import works.merc.keryx.app.resources.home_no_folder
import works.merc.keryx.app.resources.home_open_site
import works.merc.keryx.app.resources.home_refresh
import works.merc.keryx.app.resources.home_rename_feed
import works.merc.keryx.app.resources.home_unsubscribe_menu
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
import works.merc.keryx.app.ui.home.FeedListSelectionTarget
import works.merc.keryx.app.ui.home.HomeViewModel
import works.merc.keryx.app.ui.home.hasUsableUrl
import works.merc.keryx.app.ui.home.resolveFeedListSelectionTarget
import works.merc.keryx.app.ui.menu.AppMenuActions
import works.merc.keryx.app.ui.menu.AppMenuLabels
import works.merc.keryx.app.ui.menu.AppMenuNode
import works.merc.keryx.app.ui.menu.AppMenuRoot
import works.merc.keryx.app.ui.menu.AppMenuShortcut
import works.merc.keryx.app.ui.menu.MenuBarToggle
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController
import works.merc.keryx.app.ui.menu.SelectedFeedMenuData
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
    val textInputFocused by menuController.textInputFocused.collectAsState()
    val selected by homeVm.selectedArticle.collectAsState()
    val feedRefreshing by homeVm.feedRefreshing.collectAsState()
    val syncing by homeVm.syncing.collectAsState()
    val filter by homeVm.filter.collectAsState()
    val unreadOnly by homeVm.unreadOnly.collectAsState()
    val cloudConnected by homeVm.cloudConnected.collectAsState()
    val feeds by homeVm.feeds.collectAsState()
    val tags by homeVm.tags.collectAsState()
    val folders by homeVm.folders.collectAsState()
    val feedTagMap by homeVm.feedTagMap.collectAsState()

    val selectedFeed = (filter as? ArticleFilter.Feed)?.let { f -> feeds.find { it.id == f.feedId } }
    // Rename/delete act on any selected feed list item, so they resolve the same feed/folder/tag
    // target `FeedListPane` uses to decide which dialog to open.
    val selectionTarget = resolveFeedListSelectionTarget(filter, feeds, folders, tags)

    val ui = computeMenuUiState(
        screen = screen,
        hasSelectedArticle = selected != null,
        selectedArticleHasUrl = hasUsableUrl(selected?.url),
        feedRefreshing = feedRefreshing,
        syncing = syncing,
        cloudConnected = cloudConnected,
        filter = filter,
        unreadOnly = unreadOnly,
        hasSelectedFeed = selectedFeed != null,
        textInputFocused = textInputFocused,
        hasRenamableSelection = selectionTarget != null,
        selectedFeedHasSiteUrl = hasUsableUrl(selectedFeed?.site_url),
    )

    // Rename/delete wording follows the selected item's type. A `null` target falls back to the
    // feed wording; the two items are disabled in that case, so the text is never acted on.
    val renameLabel = when (selectionTarget) {
        is FeedListSelectionTarget.Folder -> stringResource(Res.string.home_menu_rename_folder)
        is FeedListSelectionTarget.Tag -> stringResource(Res.string.home_menu_rename_tag)
        is FeedListSelectionTarget.Feed, null -> stringResource(Res.string.home_rename_feed)
    }
    val deleteLabel = when (selectionTarget) {
        is FeedListSelectionTarget.Folder -> stringResource(Res.string.home_menu_delete_folder)
        is FeedListSelectionTarget.Tag -> stringResource(Res.string.home_menu_delete_tag)
        is FeedListSelectionTarget.Feed, null -> stringResource(Res.string.home_unsubscribe_menu)
    }

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
        feedRefresh = stringResource(Res.string.home_refresh),
        feedAssignTags = stringResource(Res.string.home_assign_tags),
        feedMoveToFolder = stringResource(Res.string.home_move_to_folder),
        feedNoFolder = stringResource(Res.string.home_no_folder),
        feedRename = renameLabel,
        feedUnsubscribe = deleteLabel,
        feedCopyUrl = stringResource(Res.string.home_copy_feed_url),
        feedCopySiteUrl = stringResource(Res.string.home_copy_site_url),
        feedOpenSite = stringResource(Res.string.home_open_site),
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
        refreshSelectedFeed = { selectedFeed?.let { homeVm.refreshFeed(it) } },
        toggleFeedTag = { tagId, attached -> selectedFeed?.let { homeVm.setFeedTag(it.id, tagId, attached) } },
        moveFeedToFolder = { folderId -> selectedFeed?.let { homeVm.moveFeed(it.id, folderId) } },
        renameSelectedFeed = { menuController.send(MenuCommand.RenameFeed) },
        unsubscribeSelectedFeed = { menuController.send(MenuCommand.UnsubscribeFeed) },
        copyFeedUrl = { menuController.send(MenuCommand.CopyFeedUrl) },
        copyFeedSiteUrl = { menuController.send(MenuCommand.CopySiteUrl) },
        openFeedSite = { selectedFeed?.site_url?.takeIf { hasUsableUrl(it) }?.let(BrowserOpener::open) },
        openWebsite = { BrowserOpener.open(websiteUrl) },
        openProjectPage = { BrowserOpener.open(PROJECT_URL) },
        about = { menuController.send(MenuCommand.About) },
    )

    val selectedFeedMenu = SelectedFeedMenuData(
        tags = tags,
        attachedTagIds = selectedFeed?.let { feedTagMap[it.id] }.orEmpty(),
        folders = folders,
        currentFolderId = selectedFeed?.folder_id,
    )
    val tree = buildAppMenuTree(ui, labels, actions, menuBarToggle, selectedFeedMenu)

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
            is AppMenuNode.Menu -> Menu(node.label, enabled = node.enabled) { renderNodes(node.items) }
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

/**
 * ⌘ on macOS, Ctrl elsewhere, when [AppMenuShortcut.ctrl] is set (the platform "mod" every
 * always-available and Ctrl+Shift selected-item shortcut uses) — omitted entirely when it's
 * `false` (`FeedRename`/`FeedUnsubscribe`'s bare F2/Return/Delete). [shift] applies independently.
 */
private fun AppMenuShortcut.toKeyShortcut(): KeyShortcut =
    KeyShortcut(key, meta = ctrl && isMacOs, ctrl = ctrl && !isMacOs, shift = shift)
