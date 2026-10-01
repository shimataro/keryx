package works.merc.keryx.app.presentation.menu

import works.merc.keryx.app.domain.ActivitySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MenuStateTest {

    private fun state(
        onHome: Boolean = true,
        hasSelectedArticle: Boolean = false,
        selectedArticleHasUrl: Boolean = false,
        selectedArticleCanOpenInBrowser: Boolean = false,
        activity: ActivitySnapshot = ActivitySnapshot(),
        canSyncNow: Boolean = false,
        searchActive: Boolean = false,
        unreadOnly: Boolean = false,
        hasSelectedFeed: Boolean = false,
        textInputFocused: Boolean = false,
        hasRenamableSelection: Boolean = false,
        selectedFeedHasSiteUrl: Boolean = false,
        selectedFeedSiteCanOpenInBrowser: Boolean = false,
    ) = computeMenuUiState(
        onHome = onHome,
        hasSelectedArticle = hasSelectedArticle,
        selectedArticleHasUrl = selectedArticleHasUrl,
        selectedArticleCanOpenInBrowser = selectedArticleCanOpenInBrowser,
        activity = activity,
        canSyncNow = canSyncNow,
        searchActive = searchActive,
        unreadOnly = unreadOnly,
        hasSelectedFeed = hasSelectedFeed,
        textInputFocused = textInputFocused,
        hasRenamableSelection = hasRenamableSelection,
        selectedFeedHasSiteUrl = selectedFeedHasSiteUrl,
        selectedFeedSiteCanOpenInBrowser = selectedFeedSiteCanOpenInBrowser,
    )

    // --- Home gating ---

    @Test
    fun home_enables_home_scoped_items() {
        val ui = state(onHome = true)
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
            onHome = false,
            hasSelectedArticle = true,
            selectedArticleHasUrl = true,
            selectedArticleCanOpenInBrowser = true,
            canSyncNow = true,
            hasSelectedFeed = true,
            hasRenamableSelection = true,
            selectedFeedHasSiteUrl = true,
            selectedFeedSiteCanOpenInBrowser = true,
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
        assertFalse(ui.copyUrlEnabled)
        assertFalse(ui.openInBrowserEnabled)
        assertFalse(ui.feedActionsEnabled)
        assertFalse(ui.renameOrDeleteEnabled)
        assertFalse(ui.feedSiteCopyEnabled)
        assertFalse(ui.feedSiteOpenEnabled)
    }

    // --- Article actions require a selection ---

    @Test
    fun article_actions_require_selection() {
        assertFalse(state(hasSelectedArticle = false).articleActionsEnabled)
        assertTrue(state(hasSelectedArticle = true).articleActionsEnabled)
    }

    @Test
    fun copy_url_requires_selection_with_url() {
        assertFalse(state(hasSelectedArticle = true, selectedArticleHasUrl = false).copyUrlEnabled)
        assertFalse(state(hasSelectedArticle = false, selectedArticleHasUrl = true).copyUrlEnabled)
        assertTrue(state(hasSelectedArticle = true, selectedArticleHasUrl = true).copyUrlEnabled)
    }

    @Test
    fun open_in_browser_requires_selection_with_an_http_url() {
        assertFalse(state(hasSelectedArticle = true, selectedArticleCanOpenInBrowser = false).openInBrowserEnabled)
        assertFalse(state(hasSelectedArticle = false, selectedArticleCanOpenInBrowser = true).openInBrowserEnabled)
        assertTrue(state(hasSelectedArticle = true, selectedArticleCanOpenInBrowser = true).openInBrowserEnabled)
    }

    @Test
    fun a_non_http_url_disables_open_in_browser_while_copy_stays_enabled() {
        // e.g. a `file:` or relative link: non-blank (copyable) but never handed to the OS.
        val ui = state(hasSelectedArticle = true, selectedArticleHasUrl = true, selectedArticleCanOpenInBrowser = false)
        assertTrue(ui.copyUrlEnabled)
        assertFalse(ui.openInBrowserEnabled)
    }

    @Test
    fun article_actions_disabled_away_from_home_even_with_selection() {
        val ui = state(
            onHome = false,
            hasSelectedArticle = true,
            selectedArticleHasUrl = true,
            selectedArticleCanOpenInBrowser = true,
        )
        assertFalse(ui.articleActionsEnabled)
        assertFalse(ui.copyUrlEnabled)
        assertFalse(ui.openInBrowserEnabled)
    }

    @Test
    fun article_and_url_actions_require_a_selected_article_with_url() {
        // articleActionsEnabled/copyUrlEnabled/openInBrowserEnabled require only a selection (and
        // URL) — computeMenuUiState has no pane-focus input to gate them on, unlike
        // feedActionsEnabled/renameOrDeleteEnabled's textInputFocused guard. See MenuUiState.kt's
        // articleActionsEnabled doc for why.
        val ui = state(hasSelectedArticle = true, selectedArticleHasUrl = true, selectedArticleCanOpenInBrowser = true)
        assertTrue(ui.articleActionsEnabled)
        assertTrue(ui.copyUrlEnabled)
        assertTrue(ui.openInBrowserEnabled)
    }

    // --- Sort / search interaction ---

    @Test
    fun toggle_sort_disabled_while_searching() {
        assertFalse(state(searchActive = true).toggleSortEnabled)
        assertTrue(state(searchActive = false).toggleSortEnabled)
    }

    // --- Unread-only is enabled uniformly regardless of filter/search state ---

    @Test
    fun unread_only_enabled_regardless_of_search_state() {
        assertTrue(state(searchActive = false).unreadOnlyEnabled)
        assertTrue(state(searchActive = true).unreadOnlyEnabled)
    }

    @Test
    fun unread_only_disabled_away_from_home() {
        assertFalse(state(onHome = false).unreadOnlyEnabled)
    }

    // --- Refresh / sync gating ---

    @Test
    fun refresh_all_disabled_while_refreshing() {
        assertTrue(state(activity = ActivitySnapshot()).refreshAllEnabled)
        assertFalse(state(activity = ActivitySnapshot(feedRefreshCount = 1)).refreshAllEnabled)
    }

    @Test
    fun refresh_all_also_disabled_while_syncing() {
        // Mirrors FeedListPane's toolbar buttons, which block Refresh while a sync is running —
        // running both at once isn't supported (see ActivitySnapshot.idle).
        assertFalse(state(activity = ActivitySnapshot(syncCount = 1)).refreshAllEnabled)
    }

    @Test
    fun sync_follows_can_sync_now() {
        // canSyncNow is ManualSync's one predicate — shared with Home's toolbar button and the
        // cloud-sync settings tab — so the menu adds nothing of its own beyond onHome.
        assertFalse(state(canSyncNow = false).syncEnabled)
        assertTrue(state(canSyncNow = true).syncEnabled)
    }

    @Test
    fun sync_disabled_on_an_authorization_failure_even_while_connected_and_idle() {
        // An authorization failure leaves the app connected and idle; only canSyncNow knows to
        // disable sync then, and the menu must agree with the toolbar and Settings.
        assertFalse(state(canSyncNow = false, activity = ActivitySnapshot()).syncEnabled)
    }

    @Test
    fun sync_disabled_away_from_home_even_when_can_sync_now() {
        assertFalse(state(onHome = false, canSyncNow = true).syncEnabled)
    }

    @Test
    fun refresh_all_disabled_while_a_refresh_cycle_is_running() {
        // The gap between a cycle's refresh and its sync has neither per-operation flag up, but the
        // cycle as a whole is still busy.
        val ui = state(activity = ActivitySnapshot(refreshCycleCount = 1))
        assertFalse(ui.refreshAllEnabled)
    }

    // --- Feed actions require Home + a selected feed ---

    @Test
    fun feed_actions_require_home_and_a_selected_feed() {
        assertTrue(state(hasSelectedFeed = true).feedActionsEnabled)
        assertFalse(state(onHome = false, hasSelectedFeed = true).feedActionsEnabled)
        assertFalse(state(hasSelectedFeed = false).feedActionsEnabled)
    }

    @Test
    fun feed_actions_disabled_while_the_search_field_has_focus_even_with_a_feed_selected() {
        // Rename/Unsubscribe's app-menu accelerator is a bare F2/Delete with no equivalent to
        // KeyboardNav.kt's textInputFocused suppression, so this flag has to do that job instead.
        val ui = state(hasSelectedFeed = true, textInputFocused = true)
        assertFalse(ui.feedActionsEnabled)
    }

    // --- Feed site-URL actions (copy site URL / open site) additionally require a site URL ---

    @Test
    fun feed_site_copy_requires_a_selected_feed_with_a_site_url() {
        assertFalse(state(hasSelectedFeed = true, selectedFeedHasSiteUrl = false).feedSiteCopyEnabled)
        assertFalse(state(hasSelectedFeed = false, selectedFeedHasSiteUrl = true).feedSiteCopyEnabled)
        assertTrue(state(hasSelectedFeed = true, selectedFeedHasSiteUrl = true).feedSiteCopyEnabled)
    }

    @Test
    fun feed_site_open_requires_a_selected_feed_with_an_http_site_url() {
        assertFalse(state(hasSelectedFeed = true, selectedFeedSiteCanOpenInBrowser = false).feedSiteOpenEnabled)
        assertFalse(state(hasSelectedFeed = false, selectedFeedSiteCanOpenInBrowser = true).feedSiteOpenEnabled)
        assertTrue(state(hasSelectedFeed = true, selectedFeedSiteCanOpenInBrowser = true).feedSiteOpenEnabled)
    }

    @Test
    fun a_non_http_site_url_disables_open_site_while_copy_stays_enabled() {
        val ui = state(hasSelectedFeed = true, selectedFeedHasSiteUrl = true, selectedFeedSiteCanOpenInBrowser = false)
        assertTrue(ui.feedSiteCopyEnabled)
        assertFalse(ui.feedSiteOpenEnabled)
    }

    @Test
    fun feed_site_url_actions_disabled_away_from_home_even_with_a_site_url() {
        val ui = state(
            onHome = false,
            hasSelectedFeed = true,
            selectedFeedHasSiteUrl = true,
            selectedFeedSiteCanOpenInBrowser = true,
        )
        assertFalse(ui.feedSiteCopyEnabled)
        assertFalse(ui.feedSiteOpenEnabled)
    }

    @Test
    fun feed_site_url_actions_disabled_while_the_search_field_has_focus() {
        val ui = state(
            hasSelectedFeed = true,
            selectedFeedHasSiteUrl = true,
            selectedFeedSiteCanOpenInBrowser = true,
            textInputFocused = true,
        )
        assertFalse(ui.feedSiteCopyEnabled)
        assertFalse(ui.feedSiteOpenEnabled)
    }

    // --- Rename/delete follow the selection, whatever its type ---

    @Test
    fun rename_or_delete_requires_home_and_a_renamable_selection() {
        assertTrue(state(hasRenamableSelection = true).renameOrDeleteEnabled)
        assertFalse(state(onHome = false, hasRenamableSelection = true).renameOrDeleteEnabled)
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
        val ui = state(hasSelectedFeed = true, hasRenamableSelection = true, textInputFocused = true)
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
        assertEquals(true, state(onHome = false, unreadOnly = true).unreadOnlyChecked)
    }
}
