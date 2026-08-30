package works.merc.keryx.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The collapsed, read-only entry point into search at a narrow `PaneLayout` (see `ui/home/FeedListPane.kt`
 * and the `ui-guidelines` skill's "Adaptive pane layout" section) — [text] is shown but never
 * editable, and [onClick] always means "navigate to the search screen", never "start typing here".
 * Deliberately not a read-only [KeryxTextField]: a read-only field still shows a caret and still
 * takes focus, which would reproduce the exact confusion this bar exists to remove.
 *
 * The Android `actual` matches the visual language of M3's own collapsed search bar (same pill
 * shape, container color, and elevation as `SearchBarDefaults`), so tapping it and landing on
 * [KeryxExpandedSearchBar] reads as one continuous surface expanding, the way Search does in
 * Gmail/Drive/Photos. Desktop's `actual` never renders in production (desktop always resolves
 * `PaneLayout.Triple`, where `FeedListPane` keeps its original editable field instead of this bar
 * — see that file's own branch), and exists so `desktopTest` can render and assert this composable
 * directly.
 *
 * @param text The current query; shown as plain text (never as an editable value).
 * @param isPlaceholder Whether [text] is actually the placeholder rather than a real query — kept
 *   as its own flag (instead of checking `text.isEmpty()` at the call site) so the placeholder's
 *   dimmer color is this composable's own concern, not the caller's.
 * @param onClick Called on tap. Always means "go to the search screen" — see this composable's own
 *   KDoc above.
 * @param onClickLabel The accessibility label for the click action (TalkBack reads this instead of
 *   a bare "double tap to activate" — see the `ui-guidelines` skill's Accessibility section).
 */
@Composable
expect fun KeryxCollapsedSearchBar(
    text: String,
    isPlaceholder: Boolean,
    onClick: () -> Unit,
    onClickLabel: String,
    modifier: Modifier = Modifier,
)

/**
 * The search screen's own header at a narrow `PaneLayout`: a back arrow, an editable query field,
 * and a clear action, all on one bar — the durable continuation of [KeryxCollapsedSearchBar]'s
 * expansion. Lives above `ui/home/ArticleListPane.kt`'s `SearchListPane` results list instead of a
 * `KeryxPaneTopBar`, because `KeryxPaneTopBar`'s Android `actual` is a real M3 `TopAppBar` with a
 * fixed 64dp container height, while an input field's own minimum height (56dp) grows past that
 * once the user's font-size setting (`LocalSettings.fontSizeScale`, up to 1.4×) is applied —
 * clipping the field. This composable's own pill shape has no fixed height to clip against.
 *
 * @param query The current, editable query text — bound straight to `HomeViewModel.searchQuery`,
 *   which stays the single source of truth even though the field now lives in two possible places
 *   (this bar at a narrow layout, `FeedListPane`'s own field at `PaneLayout.Triple`).
 * @param onQueryChange Reports every edit upstream to `HomeViewModel.setSearchQuery`.
 * @param placeholder Shown when [query] is empty.
 * @param onNavigateUp Called by the leading back arrow — same "go back one step" contract as every
 *   other narrow pane's own back button (see `ArticleListPane`'s `onNavigateUp` KDoc), which here
 *   resolves to `HomeScreen`'s own `goBack()`. Unlike every other pane, one step back from *this*
 *   screen is exiting the Search scope (`HomeViewModel.exitSearchScope`) and restoring the filter
 *   active before it, not popping the navigation stack — see `ui/home/HomePaneLayout.kt`'s
 *   `homeBackAction` for why that's a distinct action.
 * @param navigateUpEnabled Whether the back arrow can act right now — passed straight through from
 *   `ArticleListPane`'s own `navigateUpEnabled`, itself driven by `homeBackAction` (which resolves
 *   to enabled even at `PaneLayout.Dual`'s otherwise-inert depth 1->2 step, since exiting Search
 *   always changes what's on screen there too).
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
    navigateUpEnabled: Boolean,
    navigateUpContentDescription: String,
    clearContentDescription: String,
    onSearchAction: () -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
)
