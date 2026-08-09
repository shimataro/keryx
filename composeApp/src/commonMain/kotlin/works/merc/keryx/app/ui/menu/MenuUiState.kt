package works.merc.keryx.app.ui.menu

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
    val unreadOnlyChecked: Boolean,
    val toggleSortEnabled: Boolean,
    val markAllReadEnabled: Boolean,
    /** Toggle read / star — require a selected article and the feed list pane not to have
     * keyboard focus (a feed can stay "selected" while the article is stale for the currently
     * focused pane; mirrors [feedActionsEnabled]'s own pane-focus requirement). */
    val articleActionsEnabled: Boolean,
    /** Open in browser / copy URL — require a selected article that has a URL, same pane-focus
     * requirement as [articleActionsEnabled]. */
    val urlActionsEnabled: Boolean,
    val refreshAllEnabled: Boolean,
    val syncEnabled: Boolean,
    val openSettingsEnabled: Boolean,
    /** Refresh/Tags/Move to folder/Rename/Unsubscribe for the selected feed — require the feed
     * list pane to actually have keyboard focus, not just a selection (a feed can stay "selected"
     * while the user has since moved focus to the article list/detail pane), and require the
     * search field not to be the thing actually holding keyboard focus (Rename/Unsubscribe's F2/
     * Delete accelerator would otherwise be live while the user is typing a search query). */
    val feedActionsEnabled: Boolean,
)

/**
 * Computes [MenuUiState] from the current app/UI state. Pure so it can be tested directly.
 *
 * Most items are gated on being on the Home screen (their targets live in Home's composition).
 * Article actions additionally require a selection and that the feed list pane not currently have
 * keyboard focus; URL actions require the selection to carry a non-blank URL on top of that. Sort
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
    filterIsSearch: Boolean,
    unreadOnly: Boolean,
    feedListFocused: Boolean = false,
    hasSelectedFeed: Boolean = false,
    searchFieldFocused: Boolean = false,
): MenuUiState {
    val onHome = screen == Screen.Home
    return MenuUiState(
        addItemsEnabled = onHome,
        opmlEnabled = screen != Screen.Setup,
        searchEnabled = onHome,
        unreadOnlyChecked = unreadOnly,
        toggleSortEnabled = onHome && !filterIsSearch,
        markAllReadEnabled = onHome,
        articleActionsEnabled = onHome && hasSelectedArticle && !feedListFocused,
        urlActionsEnabled = onHome && hasSelectedArticle && selectedArticleHasUrl && !feedListFocused,
        refreshAllEnabled = onHome && !feedRefreshing,
        syncEnabled = onHome && cloudConnected && !syncing,
        openSettingsEnabled = onHome,
        feedActionsEnabled = onHome && feedListFocused && hasSelectedFeed && !searchFieldFocused,
    )
}
