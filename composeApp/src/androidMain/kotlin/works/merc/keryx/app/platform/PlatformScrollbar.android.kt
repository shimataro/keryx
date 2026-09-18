package works.merc.keryx.app.platform

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable

// See platform/ScrollIndicatorOverlay.kt for the actual (platform-independent) implementation and
// its rationale — these two actuals only supply what differs per call site: the ScrollableState
// itself and how to keep the track clear of the content's own padding.

@Composable
actual fun BoxScope.VerticalScrollbarIfNeeded(scrollState: ScrollState) {
    ScrollIndicatorOverlay(state = scrollState, trackStartInsetPx = { 0f }, trackEndInsetPx = { 0f })
}

@Composable
actual fun BoxScope.VerticalScrollbarIfNeeded(listState: LazyListState) {
    ScrollIndicatorOverlay(
        state = listState,
        // Keeps the track clear of a LazyColumn's own contentPadding (e.g. the navigation-bar
        // inset ArticleListPane/FeedListPane apply as afterContentPadding), without reading
        // WindowInsets.safeDrawing here directly: a sibling Box's Modifier.windowInsetsPadding
        // never consumes inset for this indicator, so reading the raw inset would shrink the
        // track even where the list itself isn't actually padded by it (FeedListPane has none).
        trackStartInsetPx = { listState.layoutInfo.beforeContentPadding.toFloat() },
        trackEndInsetPx = { listState.layoutInfo.afterContentPadding.toFloat() },
    )
}
