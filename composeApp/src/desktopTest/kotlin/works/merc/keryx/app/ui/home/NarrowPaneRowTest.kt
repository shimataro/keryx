package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `NarrowPaneRow` is what keeps a home pane's scroll position across the navigation stack's
 * comings and goings at a narrow `PaneLayout` — see its own KDoc for the two separate mechanisms
 * (fixed source positions for [PaneLayout.Dual]'s slide, a saveable-state holder for
 * [PaneLayout.Single]'s real unmount) and why a `visible.forEach` loop defeats the first of them.
 *
 * The panes here are stubs rather than the real ones: what is under test is the hosting structure,
 * not any pane's own content.
 */
@OptIn(ExperimentalTestApi::class)
class NarrowPaneRowTest {

    /** A stand-in pane whose scroll position is the thing being preserved (or not). */
    @Composable
    private fun StubListPane(modifier: Modifier, onState: (LazyListState) -> Unit) {
        val state = rememberLazyListState()
        onState(state)
        LazyColumn(modifier.testTag("stub-list"), state = state) {
            items(50) { index ->
                Text("row $index", Modifier.fillMaxWidth().height(40.dp).testTag("row-$index"))
            }
        }
    }

    @Composable
    private fun Host(
        layout: PaneLayout,
        depth: Int,
        paneState: SaveableStateHolder = rememberSaveableStateHolder(),
        onArticleListState: (LazyListState) -> Unit,
    ) {
        NarrowPaneRow(visiblePanes(layout, depth), Modifier.size(360.dp, 400.dp), paneState) { pane, paneModifier ->
            when (pane) {
                HomePane.ArticleList -> StubListPane(paneModifier, onArticleListState)
                else -> Box(paneModifier.fillMaxSize().testTag("pane-${pane.name}"))
            }
        }
    }

    @Test
    fun restoresPaneScrollPositionAfterUnmount() = runDesktopComposeUiTest {
        var depth by mutableStateOf(2)
        lateinit var state: LazyListState

        setContent { Host(PaneLayout.Single, depth) { state = it } }
        waitForIdle()

        onNodeWithTag("stub-list").performMouseInput { moveTo(center); repeat(12) { scroll(3f) } }
        waitForIdle()
        val scrolled = state
        val index = scrolled.firstVisibleItemIndex
        val offset = scrolled.firstVisibleItemScrollOffset
        assertTrue(index > 0, "precondition: the list should be scrolled away from the top")

        // Drill into the article detail. At PaneLayout.Single that unmounts the article list
        // outright, discarding the LazyListState it was holding.
        depth = 3
        waitForIdle()
        depth = 2
        waitForIdle()

        assertNotSame(scrolled, state, "the pane really was unmounted, so this is a fresh state")
        assertEquals(index, state.firstVisibleItemIndex)
        assertEquals(offset, state.firstVisibleItemScrollOffset)
    }

    @Test
    fun keepsPaneCompositionAliveAcrossDualSlide() = runDesktopComposeUiTest {
        var depth by mutableStateOf(2)
        lateinit var state: LazyListState

        setContent { Host(PaneLayout.Dual, depth) { state = it } }
        waitForIdle()

        onNodeWithTag("stub-list").performMouseInput { moveTo(center); repeat(12) { scroll(3f) } }
        waitForIdle()
        val scrolled = state
        val index = scrolled.firstVisibleItemIndex
        val offset = scrolled.firstVisibleItemScrollOffset
        assertTrue(index > 0, "precondition: the list should be scrolled away from the top")

        // The sliding window keeps the article list on screen at both depths, only swapping its
        // neighbor — so it must never be disposed, let alone restored from a saved snapshot.
        depth = 3
        waitForIdle()
        depth = 2
        waitForIdle()

        assertSame(scrolled, state, "the pane stayed on screen, so it must keep the same state")
        assertEquals(index, state.firstVisibleItemIndex)
        assertEquals(offset, state.firstVisibleItemScrollOffset)
    }

    @Test
    fun hoistedPaneStateLetsACallerDiscardASavedScrollPosition() = runDesktopComposeUiTest {
        // Mirrors HomeScreen's onEnterArticleList: a feed-list row selection that *enters* the
        // article list pane (rather than returning to it) discards its saved scroll state via the
        // hoisted SaveableStateHolder, so the pane opens at the top instead of restoring where the
        // user scrolled to last time it was open.
        var depth by mutableStateOf(2)
        lateinit var state: LazyListState
        lateinit var paneState: SaveableStateHolder

        setContent {
            paneState = rememberSaveableStateHolder()
            Host(PaneLayout.Single, depth, paneState) { state = it }
        }
        waitForIdle()

        onNodeWithTag("stub-list").performMouseInput { moveTo(center); repeat(12) { scroll(3f) } }
        waitForIdle()
        assertTrue(state.firstVisibleItemIndex > 0, "precondition: scrolled away from the top")

        // Unmount the article list (depth 3), discard its saved state, then bring it back.
        depth = 3
        waitForIdle()
        paneState.removeState(HomePane.ArticleList)
        depth = 2
        waitForIdle()

        assertEquals(0, state.firstVisibleItemIndex, "the discarded state must not be restored")
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }
}
