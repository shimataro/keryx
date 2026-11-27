package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.domain.IdRemaps
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.presentation.home.FeedListRowSelection
import works.merc.keryx.app.rekeyTagForTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A merge re-keying the selected tag onto another id replaces the selected row instance's id, but it is
 * not the user selecting anything: the feed list must not scroll back to the selected row if the user
 * had scrolled away from it (external-spec §9, "Least surprise": nothing moves unprompted).
 */
@OptIn(ExperimentalTestApi::class)
class FeedListPaneIdRemapScrollTest {

    @Test
    fun aReKeyOfTheSelectedTagDoesNotScrollTheFeedListBackToIt() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        repeat(30) { i -> db.insertFeed("f$i", sortOrder = i.toLong()) }
        db.insertTag("t-old", "Tag One")
        val remaps = IdRemaps(db, Dispatchers.Unconfined)
        useHomeViewModel(driver, db, idRemaps = remaps) { fixture ->
            val vm = fixture.vm
            setContent {
                KoinApplication(configuration = koinConfiguration { modules(module { single { testMenuController } }) }) {
                    Box(Modifier.size(320.dp, 400.dp)) {
                        FeedListPane(
                            vm = vm, focused = true, dragOverlay = androidx.compose.runtime.remember { FeedDragOverlayState() },
                            onActivated = {}, onSelectionAdvance = null, onTextInputFocusChange = {},
                            hasNativeAppMenu = true,
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithText("Feed f0", useUnmergedTree = true).assertIsDisplayed()

            // The user selects the tag (below the 30 feeds): the list scrolls down to it.
            vm.selectFilter(ArticleFilter.Tag("t-old"), FeedListRowSelection.Tag("t-old"))
            waitForIdle()
            onNodeWithText("Tag One", useUnmergedTree = true).assertIsDisplayed()

            // The user then scrolls the list back up, away from the selected row.
            onRoot().performMouseInput { moveTo(center); repeat(30) { scroll(-3f) } }
            waitForIdle()
            onNodeWithText("Feed f0", useUnmergedTree = true).assertIsDisplayed()
            val tagNodes = onAllNodesWithTextCount("Tag One")
            assertEquals(0, tagNodes, "precondition: the selected tag row is scrolled out of view")

            // A sync merge re-keys the tag onto another id.
            driver.rekeyTagForTest("t-old", "t-new")
            remaps.publishFromDb()
            // The ViewModel applies the re-key on Dispatchers.Main (the Swing EDT here), which waitForIdle
            // does not drain: wait for it to land, then for the recomposition it causes.
            waitUntil("the selection followed the re-key") { vm.filter.value == ArticleFilter.Tag("t-new") }
            waitForIdle()

            onNodeWithText("Feed f0", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(0, onAllNodesWithTextCount("Tag One"), "the list stayed where the user left it")
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size
}
