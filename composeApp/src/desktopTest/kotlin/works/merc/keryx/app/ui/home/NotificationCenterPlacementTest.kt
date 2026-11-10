package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.InfoDialogText
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.presentation.home.HomeViewModel
import works.merc.keryx.app.presentation.home.NotificationAlerts
import works.merc.keryx.app.ui.i18n.resolveNotificationText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The notification center's entry point is a row in the feed list's footer ([NotificationCenterRow]),
 * always shown (with its count only while the center holds something). The article list no longer carries any entry point of
 * its own: at a layout where the feed list is a drawer, the count rides on the drawer button instead.
 */
@OptIn(ExperimentalTestApi::class)
class NotificationCenterPlacementTest {

    /**
     * A Koin context of this test's own, provided through `KoinIsolatedContext`. Not the
     * `KoinApplication` composable: that starts a *global* Koin only when none exists, and otherwise
     * reuses the existing global one and ignores its own modules, so what these tests resolved
     * depended on whichever test class had left a global Koin behind.
     */
    private fun isolatedKoin() = koinApplication {
        modules(module {
            single { testMenuController }
            single<Clock> { Clock { 0L } }
        })
    }

    /** The label of the footer row (`Res.string.home_notifications`). */
    private val rowLabel = "通知"

    /** `Res.string.notification_empty`. */
    private val emptyText = "通知はありません"

    private val notificationText = NotificationText.FeedGone("placement-test-feed")

    private fun shown(): String = runBlocking { resolveNotificationText(notificationText) }

    private fun newCenter(count: Int): NotificationCenter {
        val center = NotificationCenter()
        repeat(count) { i ->
            center.add(
                AppNotification(
                    id = "n$i",
                    level = AppNotificationLevel.WARNING,
                    text = notificationText,
                    timestampMillis = 0L,
                    action = null,
                ),
            )
        }
        return center
    }

    /**
     * Drives the panes exactly as `HomeScreen` wires them for any [layout]/navigation-stack [depth].
     * The feed list receives [notifVm] (which may be null); the article list always receives the real
     * view model. A stand-in replaces `ArticleDetailPane` (a native WebView this harness cannot host).
     */
    @Composable
    private fun ContentAreaTestHost(
        vm: HomeViewModel,
        notifVm: NotificationCenterViewModel?,
        articleNotifVm: NotificationCenterViewModel,
        layout: PaneLayout,
        depth: Int,
    ) {
        KoinIsolatedContext(context = remember { isolatedKoin() }) {
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
                                notifVm = notifVm,
                            )
                            HomePane.ArticleList -> ArticleListPane(
                                vm = vm,
                                focused = true,
                                onActivated = {},
                                modifier = Modifier.size(300.dp, 600.dp),
                                notifVm = articleNotifVm,
                            )
                            HomePane.ArticleDetail -> Box(Modifier.size(300.dp, 600.dp))
                        }
                    }
                }
            }
        }
    }

    private fun runWithHost(
        layout: PaneLayout,
        depth: Int,
        notifications: Int,
        passNotifVmToFeedList: Boolean = true,
        assertions: (labelCount: Int) -> Unit,
    ) = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        val center = newCenter(notifications)
        val notifVm = NotificationCenterViewModel(center, NotificationAlerts(center))
        useHomeViewModel(driver, db) { fixture ->
            setContent {
                ContentAreaTestHost(fixture.vm, notifVm.takeIf { passNotifVmToFeedList }, notifVm, layout, depth)
            }
            waitForIdle()
            assertions(onAllNodesWithText(rowLabel).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun theArticleListNeverShowsANotificationEntryPoint() {
        // The feed list is given no notifVm, so any "通知" node or bell icon would have to come from
        // the article list. Checked at every layout and depth, with notifications present.
        for (depth in 1..3) {
            for (layout in listOf(PaneLayout.Single, PaneLayout.Dual, PaneLayout.Triple)) {
                runWithHost(layout, depth, notifications = 3, passNotifVmToFeedList = false) { count ->
                    assertEquals(0, count, "$layout depth $depth: text node")
                }
            }
        }
    }

    @Test
    fun theArticleListHasNoBellIconEvenWithNotifications() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        val center = newCenter(2)
        val notifVm = NotificationCenterViewModel(center, NotificationAlerts(center))
        useHomeViewModel(driver, db) { fixture ->
            setContent { ContentAreaTestHost(fixture.vm, null, notifVm, PaneLayout.Dual, 2) }
            waitForIdle()
            assertEquals(0, onAllNodesWithContentDescription(rowLabel).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun theFeedListShowsExactlyOneRowWhenThereIsANotification() {
        runWithHost(PaneLayout.Triple, 1, notifications = 1) { assertEquals(1, it) }
        runWithHost(PaneLayout.Triple, 3, notifications = 5) { assertEquals(1, it) }
    }

    @Test
    fun theFeedListShowsOneRowWithoutACountWhenTheCenterIsEmpty() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        val center = newCenter(0)
        val notifVm = NotificationCenterViewModel(center, NotificationAlerts(center))
        useHomeViewModel(driver, db) { fixture ->
            setContent { ContentAreaTestHost(fixture.vm, notifVm, notifVm, PaneLayout.Triple, 1) }
            waitForIdle()
            assertEquals(1, onAllNodesWithText(rowLabel).fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithText("0").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun openingTheRowWhenEmptyShowsTheEmptyState() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        val center = newCenter(0)
        val notifVm = NotificationCenterViewModel(center, NotificationAlerts(center))
        useHomeViewModel(driver, db) { fixture ->
            setContent { ContentAreaTestHost(fixture.vm, notifVm, notifVm, PaneLayout.Triple, 1) }
            waitForIdle()
            onAllNodesWithText(emptyText).fetchSemanticsNodes().let { assertEquals(0, it.size) }

            onNodeWithText(rowLabel).performClick()
            waitForIdle()

            onNodeWithText(emptyText).assertExists()
        }
    }

    @Test
    fun narrowLayoutsShowNoRowOutsideTheDrawer() {
        // At Single and Dual the feed list is a drawer, not one of the visible panes, so the content
        // area must not grow a row of its own; the drawer button's badge carries the count there.
        for (depth in 1..3) {
            runWithHost(PaneLayout.Single, depth, notifications = 2) { assertEquals(0, it, "Single depth $depth") }
        }
        runWithHost(PaneLayout.Dual, 1, notifications = 2) { assertEquals(0, it, "Dual") }
    }

    @Test
    fun theFeedListShowsNoRowWithoutANotificationViewModel() {
        runWithHost(PaneLayout.Triple, 1, notifications = 3, passNotifVmToFeedList = false) { assertEquals(0, it) }
    }

    @Test
    fun aRowActionThatChangesNoSelectionReportsNavigationWithoutAdvancingTheSelection() = runDesktopComposeUiTest {
        // In the drawer, "navigated" closes the drawer; advancing the selection would also move the
        // keyboard's pane, which an explanation dialog (or a settings tab, or a web page) must not do.
        val (driver, db) = inMemoryDb()
        val center = NotificationCenter()
        center.add(
            AppNotification(
                id = "info",
                level = AppNotificationLevel.WARNING,
                text = notificationText,
                timestampMillis = 0L,
                action = AppNotificationAction.ShowInfoDialog(InfoDialogText.APP_TRANSLOCATED),
            ),
        )
        val notifVm = NotificationCenterViewModel(center, NotificationAlerts(center))
        var navigated = 0
        var advanced = 0
        useHomeViewModel(driver, db) { fixture ->
            setContent {
                KoinIsolatedContext(context = remember { isolatedKoin() }) {
                    FeedListPane(
                        vm = fixture.vm,
                        focused = true,
                        dragOverlay = remember { FeedDragOverlayState() },
                        onActivated = {},
                        modifier = Modifier.size(300.dp, 600.dp),
                        onSelectionAdvance = { advanced++ },
                        notifVm = notifVm,
                        onNotificationNavigated = { navigated++ },
                    )
                }
            }
            waitForIdle()

            onNodeWithText(rowLabel).performClick()
            waitForIdle()
            onNodeWithText(shown()).performClick()
            waitForIdle()

            assertEquals(1, navigated)
            assertEquals(0, advanced)
        }
    }

    @Test
    fun theRowIsAFocusableButtonThatReportsWhetherItsPanelIsOpen() = runDesktopComposeUiTest {
        // Not a selectable tab like the feed rows: a button that opens the panel, reachable with Tab.
        val center = newCenter(1)
        val notifVm = NotificationCenterViewModel(center, NotificationAlerts(center))
        setContent {
            KoinIsolatedContext(context = remember { isolatedKoin() }) {
                NotificationCenterRow(notifVm = notifVm, count = 1)
            }
        }
        waitForIdle()
        val row = onNodeWithText(rowLabel)

        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        row.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        row.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))

        row.requestFocus()
        waitForIdle()
        row.assertIsFocused()

        row.performClick()
        waitForIdle()
        onNodeWithText(rowLabel).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
    }

    @Test
    fun theRowShowsTheCountAndOpensThePanelOnClick() = runDesktopComposeUiTest {
        runPanelScenario()
    }

    private suspend fun androidx.compose.ui.test.ComposeUiTest.runPanelScenario() {
        val (driver, db) = inMemoryDb()
        val center = newCenter(2)
        val notifVm = NotificationCenterViewModel(center, NotificationAlerts(center))
        useHomeViewModel(driver, db) { fixture ->
            setContent { ContentAreaTestHost(fixture.vm, notifVm, notifVm, PaneLayout.Triple, 1) }
            waitForIdle()

            onNodeWithText("2").assertExists()
            onAllNodesWithText(shown()).fetchSemanticsNodes().let { assertEquals(0, it.size) }

            onNodeWithText(rowLabel).performClick()
            waitForIdle()

            // Both notifications render in the panel.
            assertEquals(2, onAllNodesWithText(shown()).fetchSemanticsNodes().size)
        }
    }
}
