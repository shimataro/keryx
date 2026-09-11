package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * Lays out the home panes currently [visible] side by side at a narrow [PaneLayout]
 * ([PaneLayout.Single] / [PaneLayout.Dual] — [PaneLayout.Triple] has its own resizable-divider
 * layout in `HomeScreen`), and keeps each pane's scroll position across the navigation stack's
 * comings and goings.
 *
 * [PaneLayout.Dual] always shows the same two panes (see [visiblePanes]'s own KDoc) regardless of
 * depth, so it never loses a pane's state at all — [visible] itself never changes there, and this
 * row's own emission order is what's left over from the era when it did (see below), rather than
 * something this layout still needs. [PaneLayout.Single] is the layout this row exists for:
 *
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
 * The body below is two fixed `if` blocks (one per pane that can actually appear in [visible]),
 * not a loop over [HomePane.entries] — this assumes exactly the three current entries
 * ([HomePane.FeedList]/[HomePane.ArticleList]/[HomePane.ArticleDetail]); adding a fourth needs a
 * new fixed branch here, not just a bigger loop bound.
 *
 * With both panes on screen ([PaneLayout.Dual]) they are not split evenly: the article list gets
 * a fixed [dualPaneArticleListWidth] and the reader takes everything left over, the same
 * settled-list/growing-reader asymmetry [PaneLayout.Triple] already lays out (see that function's
 * own KDoc). With only one pane on screen ([PaneLayout.Single]) it simply fills the row.
 *
 * [HomePane.FeedList] never appears in [visible] here — it's a modal navigation drawer at every
 * narrow [PaneLayout] (see `HomePaneLayout.kt`'s `feedListIsDrawer`), not a pane this row lays out —
 * so only [HomePane.ArticleList]/[HomePane.ArticleDetail] ever reach [pane].
 *
 * @param visible The panes to show, from [visiblePanes].
 * @param availableWidth The width this row has to divide between [visible] — `HomeScreen`'s own
 *   `BoxWithConstraints` `maxWidth`. Only read when more than one pane is shown.
 * @param paneState The [SaveableStateHolder] backing the mechanism above, hoisted (rather than
 *   `remember`ed internally) by `HomeScreen` — outside its `BoxWithConstraints`, alongside
 *   `drawerState` — so it isn't recreated across a [PaneLayout.Triple]<->narrow layout flip.
 *   Defaults to a freshly remembered one, so existing call sites are unaffected.
 * @param pane Renders one pane, with the [Modifier] it should be laid out with.
 */
@Composable
internal fun NarrowPaneRow(
    visible: List<HomePane>,
    availableWidth: Dp,
    modifier: Modifier = Modifier,
    paneState: SaveableStateHolder = rememberSaveableStateHolder(),
    pane: @Composable (HomePane, Modifier) -> Unit,
) {
    Row(modifier) {
        val bothVisible = visible.size > 1
        if (HomePane.ArticleList in visible) {
            val listModifier =
                if (bothVisible) Modifier.width(dualPaneArticleListWidth(availableWidth)) else Modifier.fillMaxSize()
            paneState.SaveableStateProvider(HomePane.ArticleList) { pane(HomePane.ArticleList, listModifier) }
        }
        if (HomePane.ArticleDetail in visible) {
            val detailModifier = if (bothVisible) Modifier.weight(1f) else Modifier.fillMaxSize()
            paneState.SaveableStateProvider(HomePane.ArticleDetail) { pane(HomePane.ArticleDetail, detailModifier) }
        }
    }
}
