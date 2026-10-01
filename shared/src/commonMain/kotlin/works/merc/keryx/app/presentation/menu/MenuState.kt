package works.merc.keryx.app.presentation.menu

import works.merc.keryx.app.domain.ActivitySnapshot

/**
 * Enabled/checked state for every dynamic item in the desktop application menu bar (and, for the
 * SwiftUI app, its `Commands` menu). Split out of what was `ui/menu/MenuUiState.kt`
 * (composeApp-only); [computeMenuUiState] took a Compose-only `Screen` there, replaced here by the
 * plain [onHome] boolean each UI already knows how to compute for its own navigation state.
 *
 * Kept as a plain data class computed by the pure [computeMenuUiState] so the logic is unit-testable
 * without rendering the platform's own menu-bar UI.
 */
data class MenuUiState(
    /** Add feed/folder/tag — only meaningful on Home. */
    val addItemsEnabled: Boolean,
    /** OPML import/export — available once past initial setup. */
    val opmlEnabled: Boolean,
    val searchEnabled: Boolean,
    val unreadOnlyEnabled: Boolean,
    val unreadOnlyChecked: Boolean,
    val toggleSortEnabled: Boolean,
    val markAllReadEnabled: Boolean,
    /** Toggle read / star — require a selected article. Not gated on pane focus: the real
     * article-detail toolbar stays clickable for the selected article regardless of which pane
     * has keyboard focus, so the menu mirrors that. */
    val articleActionsEnabled: Boolean,
    /** Copy URL — requires a selected article with a non-blank URL (`hasUsableUrl`). */
    val copyUrlEnabled: Boolean,
    /** Open in browser — requires a selected article whose URL is http(s) (`canOpenInBrowser`), a
     * stricter rule than [copyUrlEnabled]: any other scheme is never handed to the OS. */
    val openInBrowserEnabled: Boolean,
    val refreshAllEnabled: Boolean,
    val syncEnabled: Boolean,
    val openSettingsEnabled: Boolean,
    /** Refresh/Tags/Move to folder for the selected feed — feed-specific operations, so they
     * require a selected feed, and require the search field not to be the thing actually holding
     * keyboard focus. Not gated on the feed list pane holding focus, matching the feed row's own
     * context menu, which acts on the row regardless of pane focus. */
    val feedActionsEnabled: Boolean,
    /** Copy site URL for the selected feed — like [feedActionsEnabled] but additionally requires
     * the feed to actually have a (non-blank) site URL, mirroring [copyUrlEnabled]'s relationship to
     * [articleActionsEnabled]. "Copy feed URL" doesn't need this: a feed's own subscription URL is
     * never blank, so it uses [feedActionsEnabled] directly. */
    val feedSiteCopyEnabled: Boolean,
    /** Open site for the selected feed — like [feedSiteCopyEnabled] but requires the site URL to be
     * http(s) (`canOpenInBrowser`), the same rule as [openInBrowserEnabled]. */
    val feedSiteOpenEnabled: Boolean,
    /** Rename/Delete — unlike [feedActionsEnabled] these act on whatever feed list item is
     * selected (feed, folder or tag: `resolveFeedListSelectionTarget` resolves it and
     * `FeedListPane` opens the matching dialog), so they only require *some* renamable selection.
     * The search-field guard is the same: Rename/Delete's F2/Delete accelerator would otherwise be
     * live while the user is typing a search query. */
    val renameOrDeleteEnabled: Boolean,
)

/**
 * Computes [MenuUiState] from the current app/UI state. Pure so it can be tested directly.
 *
 * Most items are gated on [onHome] (their targets live in Home's composition). Article/URL actions
 * additionally require a selection; copying a URL requires it to be non-blank
 * ([selectedArticleHasUrl] / [selectedFeedHasSiteUrl], `hasUsableUrl`), opening it in the browser
 * requires it to be http(s) ([selectedArticleCanOpenInBrowser] / [selectedFeedSiteCanOpenInBrowser],
 * `canOpenInBrowser`). The open inputs deliberately have no default, so no caller can forget them. Sort can't be toggled
 * while the Search scope is active (search order is fixed to relevance rank). Refresh is
 * suppressed unless [activity] is [ActivitySnapshot.idle] — i.e. while a refresh, a sync, or a
 * refresh-then-sync cycle (which also covers the gap between the two), is already in flight. Sync
 * follows [canSyncNow] — `ManualSync.canSyncNow`, the one predicate every "Sync now" route shares
 * (it already covers the idle check, a connected account, connect/disconnect/reset in flight and
 * an authorization failure).
 *
 * [hasSelectedFeed] gates the feed-specific actions, while [hasRenamableSelection] gates
 * rename/delete, which act on any selected feed list item (feed, folder or tag).
 */
fun computeMenuUiState(
    onHome: Boolean,
    hasSelectedArticle: Boolean,
    selectedArticleHasUrl: Boolean,
    selectedArticleCanOpenInBrowser: Boolean,
    activity: ActivitySnapshot,
    canSyncNow: Boolean,
    searchActive: Boolean,
    unreadOnly: Boolean,
    hasSelectedFeed: Boolean = false,
    textInputFocused: Boolean = false,
    hasRenamableSelection: Boolean = false,
    selectedFeedHasSiteUrl: Boolean = false,
    selectedFeedSiteCanOpenInBrowser: Boolean,
): MenuUiState = MenuUiState(
    addItemsEnabled = onHome,
    opmlEnabled = onHome,
    searchEnabled = onHome,
    unreadOnlyEnabled = onHome,
    unreadOnlyChecked = unreadOnly,
    toggleSortEnabled = onHome && !searchActive,
    markAllReadEnabled = onHome,
    articleActionsEnabled = onHome && hasSelectedArticle,
    copyUrlEnabled = onHome && hasSelectedArticle && selectedArticleHasUrl,
    openInBrowserEnabled = onHome && hasSelectedArticle && selectedArticleCanOpenInBrowser,
    refreshAllEnabled = onHome && activity.idle,
    syncEnabled = onHome && canSyncNow,
    openSettingsEnabled = onHome,
    feedActionsEnabled = onHome && hasSelectedFeed && !textInputFocused,
    renameOrDeleteEnabled = onHome && hasRenamableSelection && !textInputFocused,
    feedSiteCopyEnabled = onHome && hasSelectedFeed && !textInputFocused && selectedFeedHasSiteUrl,
    feedSiteOpenEnabled = onHome && hasSelectedFeed && !textInputFocused && selectedFeedSiteCanOpenInBrowser,
)
