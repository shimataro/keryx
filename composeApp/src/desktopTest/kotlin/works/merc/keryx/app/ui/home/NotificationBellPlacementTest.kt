package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.inMemoryDb
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The notification bell's entry point must be reachable from every list-level destination, and
 * must never be drawn twice.
 *
 * The bell used to live only in `ArticleListPane`'s header — fine on desktop, which always
 * resolves [PaneLayout.Triple] with all three panes on screen, but at [PaneLayout.Single] the
 * three panes are three *screens*, so the bell simply wasn't on the one the app can launch into
 * (depth 1, the feed list). `FeedListPane` now hosts it exactly when the article list isn't on
 * screen beside it — see `HomeScreen`'s own wiring, mirrored by [NarrowHomeTestHost] below.
 */
@OptIn(ExperimentalTestApi::class)
class NotificationBellPlacementTest {

    /** The `contentDescription` `NotificationsBell` gives its icon (`Res.string.home_notifications`). */
    private val bellLabel = "通知"

    /**
     * Drives `FeedListPane` + `ArticleListPane` exactly as `HomeScreen` wires them, for any
     * [layout] and navigation-stack [depth]. `ArticleDetailPane`'s own reader is a native WebView
     * this harness cannot host (see `ArticleDetailPaneTest`'s KDoc), so it is stubbed out — it
     * never carries a bell in production either.
     */
    @Composable
    private fun NarrowHomeTestHost(vm: HomeViewModel, notifVm: NotificationCenterViewModel, layout: PaneLayout, depth: Int) {
        KoinApplication(configuration = koinConfiguration { modules(module { single { testMenuController } }) }) {
            val visible = visiblePanes(layout, depth)
            Box(Modifier.size(1000.dp, 600.dp)) {
                Row(Modifier.fillMaxSize()) {
                    visible.forEach { pane ->
                        when (pane) {
                            HomePane.FeedList -> FeedListPane(
                                vm = vm,
                                focused = true,
                                dragOverlay = remember { FeedDragOverlayState() },
                                onActivated = {},
                                modifier = Modifier.size(300.dp, 600.dp),
                                onSelectionAdvance = if (layout == PaneLayout.Triple) null else ({}),
                                notifVm = notifVm.takeIf { HomePane.ArticleList !in visible },
                            )
                            HomePane.ArticleList -> ArticleListPane(
                                vm = vm,
                                focused = true,
                                onActivated = {},
                                modifier = Modifier.size(300.dp, 600.dp),
                                notifVm = notifVm,
                            )
                            HomePane.ArticleDetail -> Box(Modifier.size(300.dp, 600.dp))
                        }
                    }
                }
            }
        }
    }

    private fun runWithHost(layout: PaneLayout, depth: Int, assertBellCount: (Int) -> Unit) = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        val notifVm = NotificationCenterViewModel(NotificationCenter())
        useHomeViewModel(driver, db) { fixture ->
            setContent { NarrowHomeTestHost(fixture.vm, notifVm, layout, depth) }
            waitForIdle()
            assertBellCount(onAllNodesWithContentDescription(bellLabel).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun theBellIsOnScreenExactlyOnceAtEveryNarrowDepthThatHasAListPane() {
        // Single depth 1 is the case that used to have none at all: the feed list is a screen of
        // its own there, and it is where the app launches whenever lastFocusedPane says so.
        runWithHost(PaneLayout.Single, 1) { assertEquals(1, it, "Single depth 1") }
        runWithHost(PaneLayout.Single, 2) { assertEquals(1, it, "Single depth 2") }
    }

    @Test
    fun theBellIsAbsentOnTheArticleDetailScreen() {
        // Deliberate: a reading screen carries no notification entry point, matching how Android's
        // own apps treat a detail destination.
        runWithHost(PaneLayout.Single, 3) { assertEquals(0, it, "Single depth 3") }
    }

    @Test
    fun theBellIsNeverDrawnTwiceWhereBothListPanesAreOnScreen() {
        // Dual keeps the article list visible at every depth (see visiblePanes' sliding-window
        // KDoc), and Triple shows all three — the feed list must not add a second bell in either.
        for (depth in 1..3) {
            runWithHost(PaneLayout.Dual, depth) { assertEquals(1, it, "Dual depth $depth") }
            runWithHost(PaneLayout.Triple, depth) { assertEquals(1, it, "Triple depth $depth") }
        }
    }
}
