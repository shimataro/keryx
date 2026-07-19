package works.merc.keryx.app.ui.menu

import works.merc.keryx.app.ui.navigation.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MenuUiStateTest {

    private fun state(
        screen: Screen = Screen.Home,
        hasSelectedArticle: Boolean = false,
        selectedArticleHasUrl: Boolean = false,
        feedRefreshing: Boolean = false,
        syncing: Boolean = false,
        cloudConnected: Boolean = false,
        filterIsSearch: Boolean = false,
        unreadOnly: Boolean = false,
    ) = computeMenuUiState(
        screen = screen,
        hasSelectedArticle = hasSelectedArticle,
        selectedArticleHasUrl = selectedArticleHasUrl,
        feedRefreshing = feedRefreshing,
        syncing = syncing,
        cloudConnected = cloudConnected,
        filterIsSearch = filterIsSearch,
        unreadOnly = unreadOnly,
    )

    // --- Screen gating ---

    @Test
    fun home_enables_home_scoped_items() {
        val ui = state(screen = Screen.Home)
        assertTrue(ui.addItemsEnabled)
        assertTrue(ui.opmlEnabled)
        assertTrue(ui.searchEnabled)
        assertTrue(ui.markAllReadEnabled)
        assertTrue(ui.toggleSortEnabled)
        assertTrue(ui.openSettingsEnabled)
    }

    @Test
    fun setup_disables_everything_including_opml() {
        val ui = state(
            screen = Screen.Setup,
            hasSelectedArticle = true,
            selectedArticleHasUrl = true,
            cloudConnected = true,
        )
        assertFalse(ui.addItemsEnabled)
        assertFalse(ui.opmlEnabled)
        assertFalse(ui.searchEnabled)
        assertFalse(ui.markAllReadEnabled)
        assertFalse(ui.toggleSortEnabled)
        assertFalse(ui.openSettingsEnabled)
        assertFalse(ui.refreshAllEnabled)
        assertFalse(ui.syncEnabled)
        assertFalse(ui.articleActionsEnabled)
        assertFalse(ui.urlActionsEnabled)
    }

    // --- Article actions require a selection ---

    @Test
    fun article_actions_require_selection() {
        assertFalse(state(hasSelectedArticle = false).articleActionsEnabled)
        assertTrue(state(hasSelectedArticle = true).articleActionsEnabled)
    }

    @Test
    fun url_actions_require_selection_with_url() {
        assertFalse(state(hasSelectedArticle = true, selectedArticleHasUrl = false).urlActionsEnabled)
        assertFalse(state(hasSelectedArticle = false, selectedArticleHasUrl = true).urlActionsEnabled)
        assertTrue(state(hasSelectedArticle = true, selectedArticleHasUrl = true).urlActionsEnabled)
    }

    @Test
    fun article_actions_disabled_away_from_home_even_with_selection() {
        val ui = state(screen = Screen.Setup, hasSelectedArticle = true, selectedArticleHasUrl = true)
        assertFalse(ui.articleActionsEnabled)
        assertFalse(ui.urlActionsEnabled)
    }

    // --- Sort / search interaction ---

    @Test
    fun toggle_sort_disabled_in_search_scope() {
        assertFalse(state(filterIsSearch = true).toggleSortEnabled)
        assertTrue(state(filterIsSearch = false).toggleSortEnabled)
    }

    // --- Refresh / sync gating ---

    @Test
    fun refresh_all_disabled_while_refreshing() {
        assertTrue(state(feedRefreshing = false).refreshAllEnabled)
        assertFalse(state(feedRefreshing = true).refreshAllEnabled)
    }

    @Test
    fun sync_requires_connection_and_not_syncing() {
        assertFalse(state(cloudConnected = false, syncing = false).syncEnabled)
        assertFalse(state(cloudConnected = true, syncing = true).syncEnabled)
        assertTrue(state(cloudConnected = true, syncing = false).syncEnabled)
    }

    // --- Checkbox passthrough ---

    @Test
    fun unread_only_checked_mirrors_input() {
        assertTrue(state(unreadOnly = true).unreadOnlyChecked)
        assertFalse(state(unreadOnly = false).unreadOnlyChecked)
    }

    @Test
    fun unread_only_checked_reflects_state_even_off_home() {
        // The checkbox reflects the persisted toggle regardless of the active screen.
        assertEquals(true, state(screen = Screen.Setup, unreadOnly = true).unreadOnlyChecked)
    }
}
