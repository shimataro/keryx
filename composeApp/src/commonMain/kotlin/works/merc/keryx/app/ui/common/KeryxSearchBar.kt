package works.merc.keryx.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The search screen's own header at a narrow `PaneLayout`: a back arrow, an editable query field,
 * and a clear action, all on one bar. Lives above `ui/home/ArticleListPane.kt`'s article list
 * instead of a `KeryxPaneTopBar`, because `KeryxPaneTopBar`'s Android `actual` is a real M3
 * `TopAppBar` with a fixed 64dp container height, while an input field's own minimum height (56dp)
 * grows past that once the user's font-size setting (`LocalSettings.fontSizeScale`, up to 1.4×)
 * is applied — clipping the field. This composable's own pill shape has no fixed height to clip
 * against.
 *
 * @param query The current, editable query text — bound straight to `HomeViewModel.searchQuery`,
 *   which stays the single source of truth even though the field now lives in two possible places
 *   (this bar at a narrow layout, `FeedListPane`'s own field at `PaneLayout.Triple`).
 * @param onQueryChange Reports every edit upstream to `HomeViewModel.setSearchQuery`.
 * @param placeholder Shown when [query] is empty.
 * @param onNavigateUp Called by the leading back arrow — resolves to `HomeScreen`'s own
 *   `ArticleListPane`'s `onExitSearch`, itself `vm.setSearchBarVisible(false)` (see
 *   `ui/home/HomePaneLayout.kt`'s `homeBackAction` for why closing the search bar is a distinct
 *   action from popping the navigation stack).
 * @param navigateUpContentDescription Accessibility label for the back arrow.
 * @param clearContentDescription Accessibility label for the clear ("×") action, shown only when
 *   [query] is non-empty.
 * @param onSearchAction Called when the IME's "Search" action fires. Search itself already runs
 *   on every keystroke via a debounce (`HomeViewModel`'s `SEARCH_DEBOUNCE_MS`), so this is only
 *   asked to dismiss the keyboard and give the results list more room.
 * @param fieldModifier Applied to the inner editable field specifically (not the bar as a whole)
 *   so a caller's `focusRequester`/`onFocusChanged` behaves the same way it does on [KeryxTextField].
 */
@Composable
expect fun KeryxExpandedSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onNavigateUp: () -> Unit,
    navigateUpContentDescription: String,
    clearContentDescription: String,
    onSearchAction: () -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
)
