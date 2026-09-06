package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier

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
 * [HomePane.FeedList] never appears in [visible] here — it's a modal navigation drawer at every
 * narrow [PaneLayout] (see `HomePaneLayout.kt`'s `feedListIsDrawer`), not a pane this row lays out —
 * so only [HomePane.ArticleList]/[HomePane.ArticleDetail] ever reach [pane].
 *
 * @param visible The panes to show, from [visiblePanes].
 * @param paneState The [SaveableStateHolder] backing the mechanism above, hoisted (rather than
 *   `remember`ed internally) by `HomeScreen` — outside its `BoxWithConstraints`, alongside
 *   `drawerState` — so it isn't recreated across a [PaneLayout.Triple]<->narrow layout flip.
 *   Defaults to a freshly remembered one, so existing call sites are unaffected.
 * @param pane Renders one pane, with the [Modifier] it should be laid out with.
 */
@Composable
internal fun NarrowPaneRow(
    visible: List<HomePane>,
    modifier: Modifier = Modifier,
    paneState: SaveableStateHolder = rememberSaveableStateHolder(),
    pane: @Composable (HomePane, Modifier) -> Unit,
) {
    require(HomePane.entries.size == 3) {
        "NarrowPaneRow's unrolled pane layout assumes exactly three HomePane entries; " +
            "add a new fixed branch when expanding HomePane."
    }
    require(HomePane.FeedList !in visible) {
        "The feed list is a modal navigation drawer at a narrow PaneLayout, not a NarrowPaneRow " +
            "pane; see HomePaneLayout.kt's feedListIsDrawer."
    }
    Row(modifier) {
        val paneModifier = if (visible.size > 1) Modifier.weight(1f) else Modifier.fillMaxSize()
        if (HomePane.ArticleList in visible) {
            paneState.SaveableStateProvider(HomePane.ArticleList) { pane(HomePane.ArticleList, paneModifier) }
        }
        if (HomePane.ArticleDetail in visible) {
            paneState.SaveableStateProvider(HomePane.ArticleDetail) { pane(HomePane.ArticleDetail, paneModifier) }
        }
    }
}
