package works.merc.keryx.app.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.home_new_articles
import works.merc.keryx.app.ui.common.FlatTonalButton
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons

/** How long [count] must stay positive before [NewArticlesPill] actually appears — see its own KDoc. */
private const val NEW_ARTICLES_PILL_SHOW_DELAY_MS = 200L

/**
 * A floating pill announcing articles that arrived (or were merged in by a sync) outside the
 * viewport, tapped to jump to them. Composed unconditionally as a sibling of the article list's
 * `LazyColumn` — never one of its items — so it costs the list's own reuse pool no `LayoutNode`s
 * (see `known-issues.md`'s article-list reuse crash) and its own mount/unmount never disturbs the
 * list's layout (see the `ui-guidelines` skill's "Layout stability under state changes").
 *
 * [count] going from `0` to positive is deliberately not shown immediately: the article list's own
 * visible-id report lands a frame after a new query result does (`ArticleListPane`'s
 * `snapshotFlow { listState.layoutInfo... }`), so an article that lands *inside* the current
 * viewport would otherwise flash the pill for a single frame before the visibility report catches
 * up and drops it back out of the count. [showDelayMillis] absorbs that gap; going back to `0` is
 * always immediate (nothing to debounce there).
 *
 * @param count Unseen-article count. `0` renders nothing (transparent, not absent — see below).
 * @param up Which end of the list the new articles landed at (`ArticleListPane`'s own
 *   `newestFirst`) — decides the arrow glyph; the caller decides the pill's on-screen alignment.
 * @param onClick The pill's tap action — the caller both clears the count and scrolls the list.
 * @param showDelayMillis Overridable only so a test can render with `0` and skip the delay; every
 *   real call site relies on the default.
 */
@Composable
internal fun NewArticlesPill(
    count: Int,
    up: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDelayMillis: Long = NEW_ARTICLES_PILL_SHOW_DELAY_MS,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(count) {
        if (count <= 0) {
            shown = false
        } else {
            delay(showDelayMillis)
            shown = true
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f)

    // alpha == 0f is left undrawn entirely, not merely invisible — an invisible-but-present button
    // would still sit in the layout and intercept taps meant for the list beneath it.
    if (alpha <= 0f) return

    FlatTonalButton(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KeryxIcon(
                if (up) KeryxIcons.ArrowUpward else KeryxIcons.ArrowDownward,
                // The label Text right after it announces the action; a second announcement of
                // the same thing on the icon itself would be redundant (see ui-guidelines'
                // Accessibility section).
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp),
            )
            // FlatTonalButton's own content already carries labelLarge (see its KDoc); no
            // explicit style needed here.
            Text(pluralStringResource(Res.plurals.home_new_articles, count, count))
        }
    }
}
