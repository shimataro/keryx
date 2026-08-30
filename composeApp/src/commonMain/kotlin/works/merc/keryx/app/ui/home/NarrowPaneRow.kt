package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier

/**
 * Lays out the home panes currently [visible] side by side at a narrow [PaneLayout]
 * ([PaneLayout.Single] / [PaneLayout.Dual] — [PaneLayout.Triple] has its own resizable-divider
 * layout in `HomeScreen`), and keeps each pane's scroll position across the navigation stack's
 * comings and goings.
 *
 * Two separate mechanisms are at work, one per layout, because the two lose a pane's state for
 * different reasons:
 *
 * - **[PaneLayout.Dual]** never unmounts the article list — [visiblePanes]' sliding window keeps it
 *   on screen at every depth — but drilling into an article moves it from index 1 to index 0 of
 *   [visible]. Emitting the panes from a `visible.forEach` loop (as this used to) gives every
 *   iteration the same compose group key, so a pane that changes position is torn down and rebuilt
 *   even though it never left the screen, discarding its `LazyListState`. Emitting each pane from
 *   its own fixed source position instead gives it a group key of its own, so it is simply never
 *   disposed and keeps its list state outright. **A pane added here must likewise get its own
 *   fixed `if`, never a loop iteration.** [visiblePanes] always returns its panes in [HomePane]
 *   ordinal order, so the unrolled form renders exactly the same `Row`.
 * - **[PaneLayout.Single]** genuinely unmounts every pane but the one on screen, so nothing can be
 *   kept alive there. [rememberSaveableStateHolder] instead saves each pane's `rememberSaveable`
 *   state (in practice its `LazyListState`, which `rememberLazyListState` stores that way) as it
 *   leaves, and restores it when the pane comes back. That restore lands as the list state's
 *   *initial* index/offset — no scroll call, no animation — which is what keeps this fix clear of
 *   the `scrollToIndexIfNeeded` code path `docs/known-issues.md` implicates in an unfixed upstream
 *   Compose crash.
 *
 * Each [HomePane] key is used at most once per composition, as `SaveableStateProvider` requires
 * (it throws when the same key is provided twice at once). `SaveableStateProvider` emits no layout
 * node of its own, so each pane stays a direct `Row` child and the `Modifier.weight` handed to
 * [pane] still applies.
 *
 * @param visible The panes to show, from [visiblePanes].
 * @param pane Renders one pane, with the [Modifier] it should be laid out with.
 */
@Composable
internal fun NarrowPaneRow(
    visible: List<HomePane>,
    modifier: Modifier = Modifier,
    pane: @Composable (HomePane, Modifier) -> Unit,
) {
    val paneState = rememberSaveableStateHolder()
    Row(modifier) {
        val paneModifier = if (visible.size > 1) Modifier.weight(1f) else Modifier.fillMaxSize()
        if (HomePane.FeedList in visible) {
            paneState.SaveableStateProvider(HomePane.FeedList) { pane(HomePane.FeedList, paneModifier) }
        }
        if (HomePane.ArticleList in visible) {
            paneState.SaveableStateProvider(HomePane.ArticleList) { pane(HomePane.ArticleList, paneModifier) }
        }
        if (HomePane.ArticleDetail in visible) {
            paneState.SaveableStateProvider(HomePane.ArticleDetail) { pane(HomePane.ArticleDetail, paneModifier) }
        }
    }
}
