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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
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
 * comings and goings at a narrow `PaneLayout` — see its own KDoc for why emitting each pane from
 * its own fixed source position (rather than a `visible.forEach` loop) is what keeps
 * [PaneLayout.Dual]'s article list pane alive across a depth change (it's one of the same two
 * panes shown at every depth there — see `visiblePanes`' own KDoc), and for the separate
 * saveable-state-holder mechanism that instead restores [PaneLayout.Single]'s real unmount.
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
        NarrowPaneRow(visiblePanes(layout, depth), 360.dp, Modifier.size(360.dp, 400.dp), paneState) { pane, paneModifier ->
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
    fun keepsPaneCompositionAliveAcrossEveryDepthAtDual() = runDesktopComposeUiTest {
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

        // visiblePanes(Dual, depth) always includes the article list, regardless of depth — so it
        // must never be disposed, let alone restored from a saved snapshot, as the depth changes.
        depth = 3
        waitForIdle()
        depth = 2
        waitForIdle()

        assertSame(scrolled, state, "the pane stayed on screen, so it must keep the same state")
        assertEquals(index, state.firstVisibleItemIndex)
        assertEquals(offset, state.firstVisibleItemScrollOffset)
    }

    /** Counts how often the stub reader is composed fresh and disposed, and where it was placed. */
    private class ReaderProbe {
        var mounts = 0
        var disposals = 0
        var left = 0f
    }

    @Composable
    private fun StubReader(modifier: Modifier, probe: ReaderProbe) {
        DisposableEffect(Unit) {
            probe.mounts++
            onDispose { probe.disposals++ }
        }
        Box(
            modifier.fillMaxSize().testTag("stub-reader").onGloballyPositioned {
                probe.left = it.boundsInParent().left
            },
        )
    }

    @Composable
    private fun PreviewHost(
        depth: Int,
        listBehind: Boolean,
        gesture: () -> ReaderBackGesture,
        probe: ReaderProbe,
        onArticleListState: (LazyListState) -> Unit,
    ) {
        NarrowPaneRow(
            visiblePanes(PaneLayout.Single, depth),
            360.dp,
            Modifier.size(360.dp, 400.dp),
            rememberSaveableStateHolder(),
            listBehind = listBehind,
            backGesture = gesture,
        ) { pane, paneModifier ->
            when (pane) {
                HomePane.ArticleList -> StubListPane(paneModifier, onArticleListState)
                else -> StubReader(paneModifier, probe)
            }
        }
    }

    @Test
    fun backPreviewComposesTheListBehindWithoutRecreatingTheReader() = runDesktopComposeUiTest {
        var depth by mutableStateOf(2)
        var listBehind by mutableStateOf(false)
        var gesture by mutableStateOf<ReaderBackGesture>(ReaderBackGesture.Idle)
        val probe = ReaderProbe()
        lateinit var state: LazyListState

        setContent { PreviewHost(depth, listBehind, { gesture }, probe) { state = it } }
        waitForIdle()
        onNodeWithTag("stub-list").performMouseInput { moveTo(center); repeat(12) { scroll(3f) } }
        waitForIdle()
        val index = state.firstVisibleItemIndex
        assertTrue(index > 0, "precondition: the list should be scrolled away from the top")

        depth = 3
        waitForIdle()
        onNodeWithTag("stub-list").assertDoesNotExist()
        assertEquals(1, probe.mounts)

        // Gesture starts: the list is composed behind the reader, at its saved scroll position,
        // and the reader is the very same composition, now offset with the swipe.
        listBehind = true
        gesture = ReaderBackGesture.Tracking(0.5f, fromRightEdge = false)
        waitForIdle()
        onNodeWithTag("stub-list").assertExists()
        assertEquals(index, state.firstVisibleItemIndex)
        assertEquals(1, probe.mounts)
        assertEquals(0, probe.disposals)
        assertTrue(probe.left > 0f, "the reader should slide rightwards with a left-edge swipe")

        // Cancelled: the list leaves again, the reader is still the same composition, back in place.
        gesture = ReaderBackGesture.Idle
        listBehind = false
        waitForIdle()
        onNodeWithTag("stub-list").assertDoesNotExist()
        assertEquals(1, probe.mounts)
        assertEquals(0, probe.disposals)
        assertEquals(0f, probe.left)
    }

    @Test
    fun committingKeepsThePreviewedListComposedAndDropsTheReader() = runDesktopComposeUiTest {
        var depth by mutableStateOf(3)
        var listBehind by mutableStateOf(false)
        var gesture by mutableStateOf<ReaderBackGesture>(ReaderBackGesture.Idle)
        val probe = ReaderProbe()
        var state: LazyListState? = null

        setContent { PreviewHost(depth, listBehind, { gesture }, probe) { state = it } }
        waitForIdle()
        assertEquals(null, state, "precondition: the reader alone is composed at depth 3")

        listBehind = true
        gesture = ReaderBackGesture.Settling(commit = true, fromRightEdge = true, progress = 1f)
        waitForIdle()
        val previewed = state

        // The commit itself: pop to depth 2 and return to Idle in the same frame (as
        // ReaderBackController does), so the list must not be torn down and rebuilt.
        depth = 2
        listBehind = false
        gesture = ReaderBackGesture.Idle
        waitForIdle()

        assertSame(previewed, state, "the previewed list must stay composed through the commit")
        onNodeWithTag("stub-reader").assertDoesNotExist()
        assertEquals(1, probe.mounts)
        assertEquals(1, probe.disposals)
    }
}
