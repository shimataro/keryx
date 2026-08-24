package works.merc.keryx.app.platform

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable

/** No-op: Android draws its own overlay scrollbar on scrollable content, unprompted. */
@Composable
actual fun BoxScope.VerticalScrollbarIfNeeded(scrollState: ScrollState) = Unit

@Composable
actual fun BoxScope.VerticalScrollbarIfNeeded(listState: LazyListState) = Unit
