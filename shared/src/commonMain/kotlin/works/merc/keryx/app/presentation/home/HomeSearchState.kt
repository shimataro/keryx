package works.merc.keryx.app.presentation.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The search pipeline's input state: the query, whether the search bar is open, and the latched
 * request to move keyboard focus into the search field.
 *
 * Holds plain state only — the derived `searchActive` flow and the FTS result pipeline stay in
 * [HomeViewModel], which keeps its own public facade over this class.
 */
internal class HomeSearchState {

    // Search is orthogonal to `_filter`, not a variant of it: the query narrows whatever filter is
    // already selected (see HomeViewModel's `searchActive`/`searchResults`), rather than displacing it. This
    // means there is nothing to restore when search ends — the filter, its row selection, and the
    // browsing context (pins/selection/cursor) were never touched in the first place.
    private val _query = MutableStateFlow("")

    /** The current search query. */
    val query: StateFlow<String> = _query

    // Whether the expanded search bar/field is open. At PaneLayout.Triple this is always true —
    // FeedListPane's own field is permanent there — kept true by HomeScreen's own
    // LaunchedEffect(layout); at a narrow layout it starts false and is toggled by the search
    // icon/back arrow (see ArticleListPane's own KDoc). The SwiftUI app's iOS search field (on its
    // article list) binds this to `.searchable`'s `isPresented`. HomeViewModel's searchActive additionally requires
    // a non-empty query, so opening the bar alone never disturbs the article list underneath it.
    private val _barVisible = MutableStateFlow(false)

    /** Whether the expanded search bar/field is open. */
    val barVisible: StateFlow<Boolean> = _barVisible.asStateFlow()

    // Requests to move keyboard focus into whichever composable currently owns the search field —
    // FeedListPane's own KeryxTextField at PaneLayout.Triple, or ArticleListPane's
    // KeryxExpandedSearchBar at a narrow layout (Cmd+F, or tapping the search icon, both call
    // requestSearchFocus()). Deliberately a *latched* StateFlow rather than a one-shot SharedFlow:
    // at a narrow layout the request is raised in the same click that opens the bar, so the
    // composable that will own the field has not composed yet — a SharedFlow emission (as this used
    // to be) is dropped silently when no collector exists yet, which is exactly what happened here.
    // The latch stays set until the field that actually gains focus consumes it
    // (consumeSearchFocusRequest()), and setSearchBarVisible(false) clears it when the bar closes
    // without any field ever consuming it, so a stale request can never steal focus from an
    // unrelated field later.
    private val _pendingFocus = MutableStateFlow(false)

    /** Whether a request to focus the search field is waiting to be consumed. */
    val pendingFocus: StateFlow<Boolean> = _pendingFocus.asStateFlow()

    /** Sets the search query. */
    fun setQuery(query: String) {
        _query.value = query
    }

    /** Opens or closes the search bar; closing it also drops an unconsumed focus request. */
    fun setBarVisible(visible: Boolean) {
        _barVisible.value = visible
        // Drops a focus request no field ever consumed (e.g. Cmd+F, then navigating elsewhere
        // before the bar composed), so it can't steal focus at whatever field appears next.
        if (!visible) _pendingFocus.value = false
    }

    /** Latches a request to focus the search field. */
    fun requestFocus() {
        _pendingFocus.value = true
    }

    /** Clears the latched focus request once a field has taken focus. */
    fun consumeFocusRequest() {
        _pendingFocus.value = false
    }
}
