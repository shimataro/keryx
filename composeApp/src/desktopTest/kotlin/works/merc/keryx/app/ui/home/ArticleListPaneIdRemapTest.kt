package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.TagFolderIdRemaps
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.rekeyFolderForTest
import works.merc.keryx.app.rekeyTagForTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `ArticleListPane` scrolls to the top when the user switches lists, not when a sync merge re-keys the
 * selected tag or folder onto another id (external-spec §9, "Least surprise": nothing moves unprompted).
 * The re-key is simulated as the merge will do it and published through [TagFolderIdRemaps], which
 * `HomeViewModel` follows by replacing the filter and keeping its selection generation.
 */
@OptIn(ExperimentalTestApi::class)
class ArticleListPaneIdRemapTest {

    private fun KeryxDatabase.insertArticleRow(id: String, feedId: String, createdAt: Long) {
        articlesQueries.insert(
            id = id, feed_id = feedId, guid = id, url = "https://article/$id", title = "Title $id",
            summary = null, content = null, author = null, published_at = null, thumbnail_url = null,
            is_read = 0L, read_at = null, is_starred = 0L, starred_at = null, cached_at = 0L,
            search_text = "", updated_at = 0L, created_at = createdAt,
        )
    }

    private fun androidx.compose.ui.test.ComposeUiTest.renderedArticleIds(): Set<String> =
        onAllNodes(
            SemanticsMatcher("an article row") { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("article-") == true
            },
        ).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.toSet()

    /** Runs [rekey] and publishes it, with the list scrolled down the selected list [selected]. */
    private fun scrolledListSurvives(selected: ArticleFilter, rekey: (driver: app.cash.sqldelight.db.SqlDriver) -> Unit, expected: ArticleFilter) =
        runDesktopComposeUiTest {
            val (driver, db) = inMemoryDb()
            db.insertTag("t-old", "News")
            db.insertFolder("d-old", "Work")
            db.insertFeed("fa", folderId = "d-old")
            db.insertFeedTag("fa", "t-old")
            repeat(40) { db.insertArticleRow("a$it", "fa", createdAt = it.toLong()) }
            val remaps = TagFolderIdRemaps(db, Dispatchers.Unconfined)
            useHomeViewModel(driver, db, idRemaps = remaps) { fixture ->
                val vm = fixture.vm
                setContent { ArticleListPane(vm = vm, focused = true, onActivated = {}, modifier = Modifier.size(360.dp, 400.dp)) }
                vm.selectFilter(selected)
                waitForIdle()

                // Newest first, so a39 is the top row — scroll until it is gone.
                onRoot().performMouseInput { moveTo(center); repeat(12) { scroll(3f) } }
                waitForIdle()
                val before = renderedArticleIds()
                assertTrue("article-a39" !in before, "scrolled past the top row")

                rekey(driver)
                remaps.publishFromDb()
                // The ViewModel applies the re-key on Dispatchers.Main (the Swing EDT here), which waitForIdle
                // does not drain: wait for it to land, then for the recomposition it causes.
                waitUntil("the selection followed the re-key") { vm.filter.value == expected }
                waitForIdle()

                assertEquals(before, renderedArticleIds(), "the list kept its scroll position")
            }
        }

    @Test
    fun aReKeyOfTheSelectedTagDoesNotScrollTheListToTheTop() =
        scrolledListSurvives(ArticleFilter.Tag("t-old"), { it.rekeyTagForTest("t-old", "t-new") }, ArticleFilter.Tag("t-new"))

    @Test
    fun aReKeyOfTheSelectedFolderDoesNotScrollTheListToTheTop() =
        scrolledListSurvives(ArticleFilter.Folder("d-old"), { it.rekeyFolderForTest("d-old", "d-new") }, ArticleFilter.Folder("d-new"))

    /** The contrast: switching to another list the user chose does scroll to its top. */
    @Test
    fun aUserSwitchToAnotherFilterScrollsToTheTop() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertTag("t-old", "News")
        db.insertFeed("fa")
        db.insertFeedTag("fa", "t-old")
        repeat(40) { db.insertArticleRow("a$it", "fa", createdAt = it.toLong()) }
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setContent { ArticleListPane(vm = vm, focused = true, onActivated = {}, modifier = Modifier.size(360.dp, 400.dp)) }
            vm.selectFilter(ArticleFilter.Tag("t-old"))
            waitForIdle()
            onRoot().performMouseInput { moveTo(center); repeat(12) { scroll(3f) } }
            waitForIdle()
            assertNotEquals(emptySet(), renderedArticleIds())
            assertTrue("article-a39" !in renderedArticleIds())

            vm.selectFilter(ArticleFilter.Feed("fa"))
            waitForIdle()

            onNodeWithTag("article-a39").assertIsDisplayed()
        }
    }
}
