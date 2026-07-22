package works.merc.keryx.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.isMacOs
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
import works.merc.keryx.app.resources.menu_view_toggle_sort
import works.merc.keryx.app.resources.menu_view_unread_only
import works.merc.keryx.app.ui.home.HomeViewModel
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController
import works.merc.keryx.app.ui.menu.computeMenuUiState
import works.merc.keryx.app.ui.navigation.Screen
import works.merc.keryx.app.ui.settings.PROJECT_URL
import works.merc.keryx.app.ui.settings.SettingsViewModel

/**
 * Desktop application menu bar. On macOS this renders in the system (screen) menu bar; on
 * Windows/Linux it renders inside the window. Item enabled/checked state is derived by the pure
 * [computeMenuUiState]; clicks dispatch either to a ViewModel directly (state-carrying singletons)
 * or through [MenuController] for actions whose state lives inside a screen's composition.
 *
 * On macOS, Settings, About and Quit are provided by the native app (Keryx) menu — via
 * `Desktop.setPreferencesHandler` / `setAboutHandler` and AWT's default Quit — so they are omitted
 * from this menu bar to avoid duplication.
 *
 * @param onCloseWindow hides the window to the tray (same as the window's close button).
 * @param onQuit terminates the application (used only off macOS).
 */
@Composable
fun FrameWindowScope.AppMenuBar(
    onCloseWindow: () -> Unit,
    onQuit: () -> Unit,
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

    val ui = computeMenuUiState(
        screen = screen,
        hasSelectedArticle = selected != null,
        selectedArticleHasUrl = selected?.url?.isNotBlank() == true,
        feedRefreshing = feedRefreshing,
        syncing = syncing,
        cloudConnected = homeVm.cloudConnected,
        filterIsSearch = filter == ArticleFilter.Search,
        unreadOnly = unreadOnly,
    )

    // ⌘ on macOS, Ctrl elsewhere.
    fun mod(key: Key) = KeyShortcut(key, meta = isMacOs, ctrl = !isMacOs)

    MenuBar {
        Menu(stringResource(Res.string.menu_file)) {
            Item(
                stringResource(Res.string.menu_file_add_feed),
                shortcut = mod(Key.N),
                enabled = ui.addItemsEnabled,
                onClick = { menuController.send(MenuCommand.AddFeed) },
            )
            Item(
                stringResource(Res.string.menu_file_add_folder),
                enabled = ui.addItemsEnabled,
                onClick = { menuController.send(MenuCommand.AddFolder) },
            )
            Item(
                stringResource(Res.string.menu_file_add_tag),
                enabled = ui.addItemsEnabled,
                onClick = { menuController.send(MenuCommand.AddTag) },
            )
            Separator()
            Item(
                stringResource(Res.string.menu_file_import_opml),
                enabled = ui.opmlEnabled,
                onClick = { settingsVm.importOpml() },
            )
            Item(
                stringResource(Res.string.menu_file_export_opml),
                enabled = ui.opmlEnabled,
                onClick = { settingsVm.exportOpml() },
            )
            Separator()
            Item(
                stringResource(Res.string.menu_file_close_window),
                shortcut = mod(Key.W),
                onClick = onCloseWindow,
            )
            // On macOS, Settings and Quit live in the native app menu (see main.kt).
            if (!isMacOs) {
                Separator()
                Item(
                    stringResource(Res.string.menu_settings),
                    shortcut = mod(Key.Comma),
                    enabled = ui.openSettingsEnabled,
                    onClick = { menuController.send(MenuCommand.OpenSettings) },
                )
                Item(
                    stringResource(Res.string.menu_file_quit),
                    onClick = onQuit,
                )
            }
        }

        Menu(stringResource(Res.string.menu_view)) {
            Item(
                stringResource(Res.string.menu_view_search),
                enabled = ui.searchEnabled,
                onClick = { menuController.send(MenuCommand.FocusSearch) },
            )
            CheckboxItem(
                stringResource(Res.string.menu_view_unread_only),
                checked = ui.unreadOnlyChecked,
                enabled = ui.searchEnabled,
                onCheckedChange = { homeVm.setUnreadOnly(it) },
            )
            Item(
                stringResource(Res.string.menu_view_toggle_sort),
                enabled = ui.toggleSortEnabled,
                onClick = { homeVm.toggleSort() },
            )
            Separator()
            Item(
                stringResource(Res.string.menu_view_mark_all_read),
                enabled = ui.markAllReadEnabled,
                onClick = { homeVm.markAllRead() },
            )
        }

        Menu(stringResource(Res.string.menu_article)) {
            Item(
                stringResource(Res.string.menu_article_toggle_read),
                enabled = ui.articleActionsEnabled,
                onClick = { selected?.let { homeVm.toggleRead(it) } },
            )
            Item(
                stringResource(Res.string.menu_article_toggle_star),
                enabled = ui.articleActionsEnabled,
                onClick = { selected?.let { homeVm.toggleStar(it) } },
            )
            Separator()
            Item(
                stringResource(Res.string.menu_article_open_in_browser),
                enabled = ui.urlActionsEnabled,
                onClick = { menuController.send(MenuCommand.OpenInBrowser) },
            )
            Item(
                stringResource(Res.string.menu_article_copy_url),
                enabled = ui.urlActionsEnabled,
                onClick = { menuController.send(MenuCommand.CopyUrl) },
            )
        }

        Menu(stringResource(Res.string.menu_feed)) {
            Item(
                stringResource(Res.string.menu_feed_refresh_all),
                shortcut = mod(Key.R),
                enabled = ui.refreshAllEnabled,
                onClick = { homeVm.refreshAll() },
            )
            Item(
                stringResource(Res.string.menu_feed_sync_now),
                enabled = ui.syncEnabled,
                onClick = { homeVm.sync() },
            )
        }

        Menu(stringResource(Res.string.menu_help)) {
            val websiteUrl = stringResource(Res.string.website_url)
            Item(
                stringResource(Res.string.menu_help_website),
                onClick = { BrowserOpener.open(websiteUrl) },
            )
            Item(
                stringResource(Res.string.menu_help_project_page),
                onClick = { BrowserOpener.open(PROJECT_URL) },
            )
            // On macOS, About lives in the native app menu (see main.kt).
            if (!isMacOs) {
                Item(
                    stringResource(Res.string.menu_help_about),
                    onClick = { menuController.send(MenuCommand.About) },
                )
            }
        }
    }
}
