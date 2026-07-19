package works.merc.keryx.app.platform

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
private fun themedScrollbarStyle(): ScrollbarStyle {
    val onSurface = MaterialTheme.colorScheme.onSurface
    return LocalScrollbarStyle.current.copy(
        unhoverColor = onSurface.copy(alpha = 0.12f),
        hoverColor = onSurface.copy(alpha = 0.5f),
    )
}

@Composable
actual fun BoxScope.VerticalScrollbarIfNeeded(scrollState: ScrollState) {
    if (scrollState.maxValue > 0) {
        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            adapter = rememberScrollbarAdapter(scrollState),
            style = themedScrollbarStyle(),
        )
    }
}

@Composable
actual fun BoxScope.VerticalScrollbarIfNeeded(listState: LazyListState) {
    if (listState.canScrollForward || listState.canScrollBackward) {
        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            adapter = rememberScrollbarAdapter(listState),
            style = themedScrollbarStyle(),
        )
    }
}
