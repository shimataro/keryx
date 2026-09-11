package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A pane's own header row — leading [navigationIcon], optional [title], trailing [actions] — used
 * by `ui/home/FeedListPane.kt`'s `FeedListToolbarRow`, `ui/home/ArticleListPane.kt`'s
 * `ArticleListTopBar`, and `ui/home/ArticleDetailPane.kt`'s `ArticleDetailToolbar`. This does
 * **not** change the app's "3 panes, no shared top bar" structure (see the `ui-guidelines` skill's
 * "Pane structure & tonal roles" section) — each pane still calls this separately, with its own
 * actions.
 *
 * Desktop's `actual` is a plain `Row` (`navigationIcon` → [title] → trailing-pinned [actions]);
 * Android's `actual` is a real M3 `TopAppBar`. [modifier] is where a
 * caller supplies its own padding — desktop's three call sites each use a different
 * padding, so this composable applies none of its own; a caller also keeps `WindowDragArea`
 * (macOS title-bar dragging) and any `WindowChrome` inset wrapped *around* this, since neither is
 * shared across all three panes.
 */
@Composable
expect fun KeryxPaneTopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleContent: (@Composable () -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
)
