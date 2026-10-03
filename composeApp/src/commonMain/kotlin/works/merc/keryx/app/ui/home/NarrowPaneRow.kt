package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * Lays out the home panes currently [visible] side by side at a narrow [PaneLayout]
 * ([PaneLayout.Single] / [PaneLayout.Dual] — [PaneLayout.Triple] has its own resizable-divider
 * layout in `HomeScreen`), and keeps each pane's scroll position across the navigation stack's
 * comings and goings.
 *
 * [PaneLayout.Dual] always shows the same two panes (see [visiblePanes]'s own KDoc) regardless of
 * depth, so it never loses a pane's state at all — [visible] itself never changes there.
 * [PaneLayout.Single] is the layout this row exists for:
 *
 * - **[PaneLayout.Single]** genuinely unmounts every pane but the one on screen (outside the
 *   reader back preview below), so nothing can be kept alive there. [rememberSaveableStateHolder] instead saves each pane's `rememberSaveable`
 *   state (in practice its `LazyListState`, which `rememberLazyListState` stores that way) as it
 *   leaves, and restores it when the pane comes back. That restore lands as the list state's
 *   *initial* index/offset — no scroll call, no animation — which is what keeps this fix clear of
 *   the `scrollToIndexIfNeeded` code path `docs/known-issues.md` implicates in an unfixed upstream
 *   Compose crash.
 *
 * Each [HomePane] key is used at most once per composition, as `SaveableStateProvider` requires
 * (it throws when the same key is provided twice at once). `SaveableStateProvider` emits no layout
 * node of its own, so each pane stays a direct child of the `Row` ([PaneLayout.Dual]) or `Box`
 * ([PaneLayout.Single], see below) and the `Modifier.weight` handed to [pane] still applies.
 *
 * The [PaneLayout.Single] body below is two fixed `if` blocks (one per pane that can actually
 * appear in [visible]), not a loop over [HomePane.entries] — this assumes exactly the three current entries
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
 * **The reader back preview ([PaneLayout.Single] only).** While Android's predictive back gesture
 * is previewing the way from the reader back to the article list ([listBehind]), the article list
 * is composed too, *behind* the reader, so the gesture can uncover it. [PaneLayout.Single]
 * therefore stacks its panes in a `Box` rather than a `Row`, emitting the article list's fixed `if`
 * first and the reader's second. Both are conditional groups at fixed source positions, so the
 * article list appearing or disappearing never changes the reader's composition identity — the
 * reader (and the native web views it keeps in fixed slots) is neither disposed nor recreated when
 * a gesture starts, is cancelled, or commits. The article list composed this way goes through the
 * same `SaveableStateProvider` key, so it shows its restored scroll position, and a commit keeps it
 * composed straight through into the article-list depth (no remount). The two panes slide by
 * `Modifier.offset` alone ([readerBackReaderOffset]/[readerBackListOffset], read at placement time
 * from [backGesture], so a frame of the gesture relayouts without recomposing) — no `graphicsLayer`
 * scale/alpha on the reader, whose native views are positioned the same way inside it. A flip
 * between [PaneLayout.Single] and [PaneLayout.Dual] switches between the `Box` and the `Row` and so
 * rebuilds both panes; on Android that flip is a configuration change that recreates the activity
 * anyway.
 *
 * @param visible The panes to show, from [visiblePanes].
 * @param availableWidth The width this row has to divide between [visible] — `HomeScreen`'s own
 *   `BoxWithConstraints` `maxWidth`. Read when more than one pane is shown, and to turn the reader
 *   back preview's fractional offsets into pixels.
 * @param paneState The [SaveableStateHolder] backing the mechanism above, hoisted (rather than
 *   `remember`ed internally) by `HomeScreen` — outside its `BoxWithConstraints`, alongside
 *   `drawerState` — so it isn't recreated across a [PaneLayout.Triple]<->narrow layout flip.
 *   Defaults to a freshly remembered one, so existing call sites are unaffected.
 * @param listBehind Whether the reader back preview is showing right now
 *   ([ReaderBackGesture.showsListBehind]). Only has an effect at [PaneLayout.Single] with the reader
 *   on screen.
 * @param backGesture The current [ReaderBackGesture], read only at placement time for the offsets.
 * @param pane Renders one pane, with the [Modifier] it should be laid out with.
 */
@Composable
internal fun NarrowPaneRow(
    visible: List<HomePane>,
    availableWidth: Dp,
    modifier: Modifier = Modifier,
    paneState: SaveableStateHolder = rememberSaveableStateHolder(),
    listBehind: Boolean = false,
    backGesture: () -> ReaderBackGesture = { ReaderBackGesture.Idle },
    pane: @Composable (HomePane, Modifier) -> Unit,
) {
    if (visible.size > 1) {
        Row(modifier) {
            paneState.SaveableStateProvider(HomePane.ArticleList) {
                pane(HomePane.ArticleList, Modifier.width(dualPaneArticleListWidth(availableWidth)))
            }
            paneState.SaveableStateProvider(HomePane.ArticleDetail) {
                pane(HomePane.ArticleDetail, Modifier.weight(1f))
            }
        }
        return
    }
    Box(modifier) {
        val readerShown = HomePane.ArticleDetail in visible
        val previewing = readerShown && listBehind
        if (HomePane.ArticleList in visible || previewing) {
            val listModifier = if (previewing) {
                Modifier.fillMaxSize().offset {
                    val gesture = backGesture()
                    IntOffset((readerBackListOffset(gesture.visualProgress, gesture.fromRightEdge) * availableWidth.toPx()).roundToInt(), 0)
                }
            } else {
                Modifier.fillMaxSize()
            }
            paneState.SaveableStateProvider(HomePane.ArticleList) { pane(HomePane.ArticleList, listModifier) }
        }
        if (readerShown) {
            val detailModifier = Modifier.fillMaxSize().offset {
                val gesture = backGesture()
                IntOffset((readerBackReaderOffset(gesture.visualProgress, gesture.fromRightEdge) * availableWidth.toPx()).roundToInt(), 0)
            }
            paneState.SaveableStateProvider(HomePane.ArticleDetail) { pane(HomePane.ArticleDetail, detailModifier) }
        }
    }
}
