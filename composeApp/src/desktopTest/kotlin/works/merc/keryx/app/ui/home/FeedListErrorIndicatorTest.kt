package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.getString
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import works.merc.keryx.app.core.FEED_ERROR_REASON_GONE
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.home_feed_error
import works.merc.keryx.app.resources.home_feed_gone
import works.merc.keryx.app.setFeedFetchState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A feed row's error indicator comes from the feed's device-local fetch state
 * (`HomeViewModel.feedFetchProblems`), not from the feed row: a failed last fetch shows the error
 * icon, a 410 Gone the gone icon, and a feed with no fetch state (or a clean one) neither.
 */
@OptIn(ExperimentalTestApi::class)
class FeedListErrorIndicatorTest {

    @Test
    fun erroringAndGoneFeedsAreFlaggedFromTheirFetchState() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("f1")
        db.insertFeed("f2")
        db.insertFeed("f3")
        db.insertFeed("f4")
        driver.setFeedFetchState("f1", errorCount = 2, lastError = "boom")
        driver.setFeedFetchState("f2", lastError = FEED_ERROR_REASON_GONE)
        driver.setFeedFetchState("f3", etag = "\"e\"")
        useHomeViewModel(driver, db) { fixture ->
            setContent {
                KoinApplication(configuration = koinConfiguration { modules(module { single { testMenuController } }) }) {
                    Box(Modifier.size(320.dp, 600.dp)) {
                        FeedListPane(vm = fixture.vm, focused = true, dragOverlay = remember { FeedDragOverlayState() }, onActivated = {})
                    }
                }
            }
            val error = getString(Res.string.home_feed_error)
            val gone = getString(Res.string.home_feed_gone)
            fun count(description: String) =
                onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes().size
            // The fetch state arrives through its own flow, off the main thread.
            waitUntil(timeoutMillis = 5_000) { count(error) > 0 && count(gone) > 0 }

            assertEquals(1, count(error))
            assertEquals(1, count(gone))
        }
    }
}
