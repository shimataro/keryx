package works.merc.keryx.app.platform

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable

@Composable
expect fun BoxScope.VerticalScrollbarIfNeeded(scrollState: ScrollState)

@Composable
expect fun BoxScope.VerticalScrollbarIfNeeded(listState: LazyListState)
