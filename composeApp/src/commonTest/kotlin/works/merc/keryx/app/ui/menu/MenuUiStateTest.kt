package works.merc.keryx.app.ui.menu

import works.merc.keryx.app.core.ArticleFilter
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
        filter: ArticleFilter = ArticleFilter.All,
        unreadOnly: Boolean = false,
        hasSelectedFeed: Boolean = false,
        searchFieldFocused: Boolean = false,
        hasRenamableSelection: Boolean = false,
    ) = computeMenuUiState(
        screen = screen,
        hasSelectedArticle = hasSelectedArticle,
        selectedArticleHasUrl = selectedArticleHasUrl,
        feedRefreshing = feedRefreshing,
        syncing = syncing,
        cloudConnected = cloudConnected,
        filter = filter,
        unreadOnly = unreadOnly,
        hasSelectedFeed = hasSelectedFeed,
        searchFieldFocused = searchFieldFocused,
        hasRenamableSelection = hasRenamableSelection,
    )

    // --- Screen gating ---

    @Test
    fun home_enables_home_scoped_items() {
        val ui = state(screen = Screen.Home)
        assertTrue(ui.addItemsEnabled)
        assertTrue(ui.opmlEnabled)
        assertTrue(ui.searchEnabled)
        assertTrue(ui.unreadOnlyEnabled)
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
            hasSelectedFeed = true,
            hasRenamableSelection = true,
        )
        assertFalse(ui.addItemsEnabled)
        assertFalse(ui.opmlEnabled)
        assertFalse(ui.searchEnabled)
        assertFalse(ui.unreadOnlyEnabled)
        assertFalse(ui.markAllReadEnabled)
        assertFalse(ui.toggleSortEnabled)
        assertFalse(ui.openSettingsEnabled)
        assertFalse(ui.refreshAllEnabled)
        assertFalse(ui.syncEnabled)
        assertFalse(ui.articleActionsEnabled)
        assertFalse(ui.urlActionsEnabled)
        assertFalse(ui.feedActionsEnabled)
        assertFalse(ui.renameOrDeleteEnabled)
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

    @Test
    fun article_and_url_actions_stay_enabled_regardless_of_feed_list_pane_focus() {
        // The real article-detail toolbar has no pane-focus dependency (it only checks the
        // selection itself), so the menu must not grey out just because the feed list pane
        // happens to hold keyboard focus for the same selected article.
        val ui = state(hasSelectedArticle = true, selectedArticleHasUrl = true)
        assertTrue(ui.articleActionsEnabled)
        assertTrue(ui.urlActionsEnabled)
    }

    // --- Sort / search interaction ---

    @Test
    fun toggle_sort_disabled_in_search_scope() {
        assertFalse(state(filter = ArticleFilter.Search).toggleSortEnabled)
        assertTrue(state(filter = ArticleFilter.All).toggleSortEnabled)
    }

    // --- Unread-only requires a filter other than Starred ---

    @Test
    fun unread_only_enabled_except_in_starred_filter() {
        assertTrue(state(filter = ArticleFilter.All).unreadOnlyEnabled)
        assertTrue(state(filter = ArticleFilter.Search).unreadOnlyEnabled)
        assertTrue(state(filter = ArticleFilter.Feed("f1")).unreadOnlyEnabled)
        assertTrue(state(filter = ArticleFilter.Tag("t1")).unreadOnlyEnabled)
        assertTrue(state(filter = ArticleFilter.Folder("fo1")).unreadOnlyEnabled)
        assertFalse(state(filter = ArticleFilter.Starred).unreadOnlyEnabled)
    }

    @Test
    fun unread_only_disabled_away_from_home_even_off_starred() {
        assertFalse(state(screen = Screen.Setup, filter = ArticleFilter.All).unreadOnlyEnabled)
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

    // --- Feed actions require Home + a selected feed ---

    @Test
    fun feed_actions_require_home_and_a_selected_feed() {
        assertTrue(state(hasSelectedFeed = true).feedActionsEnabled)
        assertFalse(state(screen = Screen.Setup, hasSelectedFeed = true).feedActionsEnabled)
        assertFalse(state(hasSelectedFeed = false).feedActionsEnabled)
    }

    @Test
    fun feed_actions_disabled_while_the_search_field_has_focus_even_with_a_feed_selected() {
        // Rename/Unsubscribe's app-menu accelerator is a bare F2/Delete with no equivalent to
        // KeyboardNav.kt's searchFieldFocused suppression, so this flag has to do that job instead.
        val ui = state(hasSelectedFeed = true, searchFieldFocused = true)
        assertFalse(ui.feedActionsEnabled)
    }

    // --- Rename/delete follow the selection, whatever its type ---

    @Test
    fun rename_or_delete_requires_home_and_a_renamable_selection() {
        assertTrue(state(hasRenamableSelection = true).renameOrDeleteEnabled)
        assertFalse(state(screen = Screen.Setup, hasRenamableSelection = true).renameOrDeleteEnabled)
        assertFalse(state(hasRenamableSelection = false).renameOrDeleteEnabled)
    }

    @Test
    fun rename_or_delete_enabled_for_a_folder_or_tag_selection_that_leaves_feed_actions_disabled() {
        // Selecting a folder or a tag resolves a rename/delete target without selecting a feed, so
        // the feed-specific actions (Refresh/Tags/Move to folder) stay disabled while these don't.
        val ui = state(hasSelectedFeed = false, hasRenamableSelection = true)
        assertTrue(ui.renameOrDeleteEnabled)
        assertFalse(ui.feedActionsEnabled)
    }

    @Test
    fun rename_or_delete_disabled_while_the_search_field_has_focus_even_with_a_selection() {
        // Same guard as feedActionsEnabled: the bare F2/Delete accelerator must not be live while
        // the user is typing a search query.
        val ui = state(hasSelectedFeed = true, hasRenamableSelection = true, searchFieldFocused = true)
        assertFalse(ui.renameOrDeleteEnabled)
        assertFalse(ui.feedActionsEnabled)
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
