package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A pane's own header row — leading [navigationIcon], optional [title], trailing [actions] — the
 * replacement for a hand-rolled `Row` at a `commonMain` call site (see `ui/home/FeedListPane.kt`'s
 * `FeedListToolbarRow`, `ui/home/ArticleListPane.kt`'s `ArticleListTopBar`, and
 * `ui/home/ArticleDetailPane.kt`'s `ArticleDetailToolbar`). This does **not** change the app's
 * "3 panes, no shared top bar" structure (see the `ui-guidelines` skill's "Pane structure & tonal
 * roles" section) — each pane still calls this separately, with its own actions.
 *
 * Desktop's `actual` reproduces each former `Row`'s exact layout (`navigationIcon` → [title] →
 * trailing-pinned [actions]); Android's `actual` is a real M3 `TopAppBar`. [modifier] is where a
 * caller supplies its own padding — desktop's three former call sites each used a different
 * padding, so this composable applies none of its own; a caller also keeps `WindowDragArea`
 * (macOS title-bar dragging) and any `WindowChrome` inset wrapped *around* this, since neither is
 * shared across all three panes.
 */
@Composable
expect fun KeryxPaneTopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
)
