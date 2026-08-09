package works.merc.keryx.app.ui.menu

import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.ui.navigation.Screen

/**
 * Enabled/checked state for every dynamic item in the desktop application menu bar.
 *
 * Kept as a plain data class computed by the pure [computeMenuUiState] so the logic is unit-testable
 * without rendering the (desktop-only, untestable) `MenuBar` composable.
 */
data class MenuUiState(
    /** Add feed/folder/tag — only meaningful on Home. */
    val addItemsEnabled: Boolean,
    /** OPML import/export — available once past initial setup. */
    val opmlEnabled: Boolean,
    val searchEnabled: Boolean,
    /** Toggling unread-only has no effect while viewing [ArticleFilter.Starred] (that filter
     * ignores the flag entirely — see `HomeViewModel`'s `articles` pipeline), mirroring
     * `isUnreadOnlyEnabled` in `ArticleListPane.kt` for the real toggle chip. */
    val unreadOnlyEnabled: Boolean,
    val unreadOnlyChecked: Boolean,
    val toggleSortEnabled: Boolean,
    val markAllReadEnabled: Boolean,
    /** Toggle read / star — require a selected article. Not gated on pane focus: the real
     * article-detail toolbar stays clickable for the selected article regardless of which pane
     * has keyboard focus, so the menu mirrors that. */
    val articleActionsEnabled: Boolean,
    /** Open in browser / copy URL — require a selected article that has a URL. */
    val urlActionsEnabled: Boolean,
    val refreshAllEnabled: Boolean,
    val syncEnabled: Boolean,
    val openSettingsEnabled: Boolean,
    /** Refresh/Tags/Move to folder/Rename/Unsubscribe for the selected feed — require a selected
     * feed, and require the search field not to be the thing actually holding keyboard focus
     * (Rename/Unsubscribe's F2/Delete accelerator would otherwise be live while the user is
     * typing a search query). Not gated on the feed list pane holding focus, matching the feed
     * row's own context menu, which acts on the row regardless of pane focus. */
    val feedActionsEnabled: Boolean,
)

/**
 * Computes [MenuUiState] from the current app/UI state. Pure so it can be tested directly.
 *
 * Most items are gated on being on the Home screen (their targets live in Home's composition).
 * Article/URL actions additionally require a selection (and a non-blank URL for the latter).
 * Unread-only additionally requires the active filter not to be [ArticleFilter.Starred]. Sort
 * can't be toggled while the Search scope is active (search order is fixed to relevance rank).
 * Refresh/sync are suppressed while their operation is already in flight, and sync additionally
 * requires a connected cloud account.
 */
fun computeMenuUiState(
    screen: Screen,
    hasSelectedArticle: Boolean,
    selectedArticleHasUrl: Boolean,
    feedRefreshing: Boolean,
    syncing: Boolean,
    cloudConnected: Boolean,
    filter: ArticleFilter,
    unreadOnly: Boolean,
    hasSelectedFeed: Boolean = false,
    searchFieldFocused: Boolean = false,
): MenuUiState {
    val onHome = screen == Screen.Home
    return MenuUiState(
        addItemsEnabled = onHome,
        opmlEnabled = screen != Screen.Setup,
        searchEnabled = onHome,
        unreadOnlyEnabled = onHome && filter != ArticleFilter.Starred,
        unreadOnlyChecked = unreadOnly,
        toggleSortEnabled = onHome && filter != ArticleFilter.Search,
        markAllReadEnabled = onHome,
        articleActionsEnabled = onHome && hasSelectedArticle,
        urlActionsEnabled = onHome && hasSelectedArticle && selectedArticleHasUrl,
        refreshAllEnabled = onHome && !feedRefreshing,
        syncEnabled = onHome && cloudConnected && !syncing,
        openSettingsEnabled = onHome,
        feedActionsEnabled = onHome && hasSelectedFeed && !searchFieldFocused,
    )
}
