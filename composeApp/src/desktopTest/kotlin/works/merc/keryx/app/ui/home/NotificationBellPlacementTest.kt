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
 * The bell lives only in `ArticleListPane`'s own header. That pane is on screen at every layout
 * except [PaneLayout.Single]'s article-detail depth — see `HomePaneLayout.kt`'s `visiblePanes` —
 * so unlike before the feed list became a modal navigation drawer (see `feedListIsDrawer`), there
 * is no narrow-layout destination the bell is unreachable from, and `FeedListPane` (which no
 * longer takes a `notifVm` at all — see [theDrawerHasNoBellOfItsOwn]) never has to host a second
 * one.
 */
@OptIn(ExperimentalTestApi::class)
class NotificationBellPlacementTest {

    /** The `contentDescription` `NotificationsBell` gives its icon (`Res.string.home_notifications`). */
    private val bellLabel = "通知"

    /**
     * Drives `ArticleListPane` (+ a stand-in for `ArticleDetailPane`, whose real reader is a
     * native WebView this harness cannot host — see `ArticleDetailPaneTest`'s KDoc) exactly as
     * `HomeScreen` wires them for any [layout]/navigation-stack [depth]. At `PaneLayout.Triple`,
     * `visiblePanes` still includes `HomePane.FeedList` as a genuine permanent pane there (unlike
     * at a narrow layout, where the feed list is a modal drawer instead — see `feedListIsDrawer`),
     * so it's rendered too, with no `notifVm` — Triple's own `FeedListPane` call never passed one,
     * since `ArticleListPane`'s bell already covers it.
     */
    @Composable
    private fun ContentAreaTestHost(vm: HomeViewModel, notifVm: NotificationCenterViewModel, layout: PaneLayout, depth: Int) {
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
            setContent { ContentAreaTestHost(fixture.vm, notifVm, layout, depth) }
            waitForIdle()
            assertBellCount(onAllNodesWithContentDescription(bellLabel).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun theBellIsOnScreenExactlyOnceWheneverTheArticleListIsVisible() {
        // Single depth 1 and 2 both resolve to the article list (see visiblePanes' own KDoc) —
        // neither is a screen the bell could be missing from any more, unlike before the feed
        // list became a drawer.
        runWithHost(PaneLayout.Single, 1) { assertEquals(1, it, "Single depth 1") }
        runWithHost(PaneLayout.Single, 2) { assertEquals(1, it, "Single depth 2") }
        // Dual keeps the article list visible at every depth, and Triple shows all three panes.
        for (depth in 1..3) {
            runWithHost(PaneLayout.Dual, depth) { assertEquals(1, it, "Dual depth $depth") }
            runWithHost(PaneLayout.Triple, depth) { assertEquals(1, it, "Triple depth $depth") }
        }
    }

    @Test
    fun theBellIsAbsentOnTheArticleDetailScreen() {
        // Deliberate: a reading screen carries no notification entry point, matching how Android's
        // own apps treat a detail destination. The only state where the article list isn't
        // visible at all (see visiblePanes' own KDoc).
        runWithHost(PaneLayout.Single, 3) { assertEquals(0, it, "Single depth 3") }
    }

    @Test
    fun theDrawerHasNoBellOfItsOwn() = runDesktopComposeUiTest {
        // FeedListPane no longer takes a notifVm parameter at all — there is nothing left that
        // could wire a second bell into the drawer, but this pins the observable outcome (no bell
        // node at all when only the drawer's own content is rendered) rather than relying solely
        // on the parameter having been removed.
        val (driver, db) = inMemoryDb()
        useHomeViewModel(driver, db) { fixture ->
            setContent {
                KoinApplication(configuration = koinConfiguration { modules(module { single { testMenuController } }) }) {
                    FeedListPane(
                        vm = fixture.vm,
                        focused = false,
                        dragOverlay = remember { FeedDragOverlayState() },
                        onActivated = {},
                        modifier = Modifier.size(320.dp, 600.dp),
                        onSelectionAdvance = {},
                    )
                }
            }
            waitForIdle()

            assertEquals(0, onAllNodesWithContentDescription(bellLabel).fetchSemanticsNodes().size)
        }
    }
}
