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
    /** Toggle read / star — require a selected article. */
    val articleActionsEnabled: Boolean,
    /** Open in browser / copy URL — require a selected article that has a URL. */
    val urlActionsEnabled: Boolean,
    val refreshAllEnabled: Boolean,
    val syncEnabled: Boolean,
    val openSettingsEnabled: Boolean,
)

/**
 * Computes [MenuUiState] from the current app/UI state. Pure so it can be tested directly.
 *
 * Most items are gated on being on the Home screen (their targets live in Home's composition).
 * Article actions additionally require a selection; URL actions require the selection to carry a
 * non-blank URL. Sort can't be toggled while the Search scope is active (search order is fixed to
 * relevance rank). Refresh/sync are suppressed while their operation is already in flight, and sync
 * additionally requires a connected cloud account.
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
): MenuUiState {
    val onHome = screen == Screen.Home
    return MenuUiState(
        addItemsEnabled = onHome,
        opmlEnabled = screen != Screen.Setup,
        searchEnabled = onHome,
        unreadOnlyChecked = unreadOnly,
        toggleSortEnabled = onHome && !filterIsSearch,
        markAllReadEnabled = onHome,
        articleActionsEnabled = onHome && hasSelectedArticle,
        urlActionsEnabled = onHome && hasSelectedArticle && selectedArticleHasUrl,
        refreshAllEnabled = onHome && !feedRefreshing,
        syncEnabled = onHome && cloudConnected && !syncing,
        openSettingsEnabled = onHome,
    )
}
