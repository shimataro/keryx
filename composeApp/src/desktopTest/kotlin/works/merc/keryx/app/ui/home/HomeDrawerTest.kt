package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.KoinApplication
import works.merc.keryx.app.inMemoryDb
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import works.merc.keryx.app.core.ArticleFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for `HomeScreen`'s narrow-layout `ModalNavigationDrawer` wiring — `ModalNavigationDrawer`
 * is a plain commonMain M3 composable, so this drives it directly with `desktopTest` instead of
 * needing a real Android device, mirroring just the pieces of `HomeScreen`'s own wiring these tests
 * exercise: `FeedListPane` as `drawerContent` with `onSelectionAdvance` closing the drawer, and
 * `LocalRowSelectionVisible` overridden to `true` inside it regardless of what the body content
 * provides (see `HomeScreen`'s own KDoc on both).
 */
@OptIn(ExperimentalTestApi::class)
class HomeDrawerTest {

    /**
     * Starts the drawer open (`DrawerValue.Open`) so a test's first frame can assert its content
     * without first driving an animation or a real swipe/tap gesture to open it.
     *
     * @param bodyProvidesSelectionVisible What the body content (behind the drawer) provides for
     *   `LocalRowSelectionVisible` — deliberately independent of the drawer's own override, so
     *   [drawerContentAlwaysShowsTheSelectionHighlightEvenWhenTheBodySuppressesIt] can prove the
     *   two don't leak into each other.
     * @param onDrawerStateReady Reports the composed `DrawerState` back to the test.
     * @param onDrawerContentRowSelectionVisible Reports the `LocalRowSelectionVisible` value
     *   actually seen inside `drawerContent`'s own composition scope.
     * @param onFocusedPaneChange Mirrors `HomeScreen`'s own `onSelectionAdvance` on the drawer's
     *   `FeedListPane` — a row selection there also moves `focusedPane` to `HomePane.ArticleList`,
     *   not just closing the drawer, so a subsequent ↑/↓ has somewhere to land (see
     *   `HomeScreen.kt`'s own comment on this call site for the bug this fixes).
     */
    @Composable
    private fun DrawerTestHost(
        vm: HomeViewModel,
        bodyProvidesSelectionVisible: Boolean = true,
        onDrawerStateReady: (DrawerState) -> Unit = {},
        onDrawerContentRowSelectionVisible: (Boolean) -> Unit = {},
        onFocusedPaneChange: (HomePane) -> Unit = {},
    ) {
        KoinApplication(configuration = koinConfiguration { modules(module { single { testMenuController } }) }) {
            val drawerState = rememberDrawerState(DrawerValue.Open)
            onDrawerStateReady(drawerState)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    CompositionLocalProvider(LocalRowSelectionVisible provides true) {
                        onDrawerContentRowSelectionVisible(LocalRowSelectionVisible.current)
                        ModalDrawerSheet(drawerState = drawerState, modifier = Modifier.size(320.dp, 600.dp)) {
                            FeedListPane(
                                vm = vm,
                                focused = false,
                                dragOverlay = remember { FeedDragOverlayState() },
                                onActivated = {},
                                onSelectionAdvance = {
                                    onFocusedPaneChange(HomePane.ArticleList)
                                    scope.launch { drawerState.close() }
                                },
                            )
                        }
                    }
                },
            ) {
                CompositionLocalProvider(LocalRowSelectionVisible provides bodyProvidesSelectionVisible) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
    }

    @Test
    fun tappingAFeedRowClosesTheDrawerAndChangesTheFilter() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            lateinit var drawerState: DrawerState
            setContent { DrawerTestHost(vm, onDrawerStateReady = { drawerState = it }) }
            waitForIdle()
            assertTrue(drawerState.isOpen)

            onNodeWithText("スター付き").performClick()
            waitForIdle()

            assertEquals(ArticleFilter.Starred, vm.filter.value)
            assertFalse(drawerState.isOpen)
        }
    }

    /**
     * Regression test for a bug where a drawer row selection left `focusedPane` wherever it was
     * before the drawer opened (possibly `HomePane.ArticleDetail`) — closing the drawer then left
     * ↑/↓ unable to move the very selection the user just made, since neither `HomePane.FeedList`
     * (the drawer, now closed) nor `HomePane.ArticleDetail` (whatever `focusedPane` was stuck at)
     * routes to the article list.
     */
    @Test
    fun tappingAFeedRowMovesFocusedPaneToTheArticleList() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            var focusedPane: HomePane? = null
            setContent { DrawerTestHost(vm, onFocusedPaneChange = { focusedPane = it }) }
            waitForIdle()

            onNodeWithText("スター付き").performClick()
            waitForIdle()

            assertEquals(HomePane.ArticleList, focusedPane)
        }
    }

    @Test
    fun drawerContentAlwaysShowsTheSelectionHighlightEvenWhenTheBodySuppressesIt() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            var rowSelectionVisibleInDrawer = false
            setContent {
                DrawerTestHost(
                    vm,
                    bodyProvidesSelectionVisible = false,
                    onDrawerContentRowSelectionVisible = { rowSelectionVisibleInDrawer = it },
                )
            }
            waitForIdle()

            assertEquals(true, rowSelectionVisibleInDrawer)
        }
    }

    @Test
    fun drawerHasNoSearchFieldOrIcon() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setContent { DrawerTestHost(vm) }
            waitForIdle()

            // FeedListPane's own field is Triple-only (onSelectionAdvance == null there); as
            // drawer content (onSelectionAdvance non-null) it renders neither an editable field
            // nor any other search entry point — that lives on ArticleListPane instead.
            onNode(hasSetTextAction()).assertDoesNotExist()
        }
    }
}
