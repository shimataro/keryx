package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.awaitCondition
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.presentation.home.HomeViewModelFixture
import works.merc.keryx.app.presentation.home.newHomeViewModel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ArticleListPane` keeps its scroll position across process restoration only while the list is the
 * same one. The previous process never switched list (its ViewModel's selection generation is 0, the
 * filter restored from the saved settings); on restoration the saved filter points at a deleted tag, so
 * the new ViewModel falls back to All — also generation 0. The list is a different one and must start
 * at the top (external-spec §9, "Least surprise"), not at the old list's offset.
 *
 * The restart is modelled by running two Compose tests in turn: the first saves its
 * [SaveableStateRegistry], the second restores from it over a new ViewModel (a new fixture over the
 * same settings directory), as `StateRestorationTester` does for a rule-based test (this module has
 * no rule-based Compose tests).
 */
@OptIn(ExperimentalTestApi::class)
class ArticleListPaneRestorationTest {

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

    /** The control: with the same list restored, the scroll position is kept (so the restoration is modelled). */
    @Test
    fun aRestoredListThatIsTheSameFilterKeepsItsScrollPosition() = restoreAfterScrolling(deleteTag = false) { rendered ->
        assertTrue("article-a39" !in rendered, "the same list keeps its offset: $rendered")
    }

    @Test
    fun aRestoredListThatIsADifferentFilterStartsAtTheTopEvenWhenBothGenerationsAreZero() = restoreAfterScrolling(deleteTag = true) { rendered ->
        assertTrue("article-a39" in rendered, "a different list starts at the top, not at the old offset: $rendered")
    }

    private fun restoreAfterScrolling(deleteTag: Boolean, check: (Set<String>) -> Unit) = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertTag("t1", "News")
        db.insertFeed("fa")
        db.insertFeedTag("fa", "t1")
        repeat(40) { db.insertArticleRow("a$it", "fa", createdAt = it.toLong()) }
        val settingsDir = FileIO.join(AppDirs.tempDir(), "restore-test-${Random.nextInt()}")

        // An earlier session: the user chose the tag, so the saved settings name it.
        newHomeViewModel(driver, db, settingsDir = settingsDir).also { it.vm.selectFilter(ArticleFilter.Tag("t1")) }.stop()

        // Process 1: starts on the restored tag (generation 0) and is scrolled down.
        val first = newHomeViewModel(driver, db, settingsDir = settingsDir)
        assertEquals(ArticleFilter.Tag("t1"), first.vm.filter.value)
        assertEquals(0L, first.vm.filterSelection.value.generation)
        // As StateRestorationTester does: the content leaves the composition (its remembered state is
        // gone), then comes back under a new registry restored from the saved values.
        var showContent by mutableStateOf(true)
        var registry by mutableStateOf(SaveableStateRegistry(restoredValues = null, canBeSaved = { true }))
        var currentVm by mutableStateOf(first.vm)
        var second: HomeViewModelFixture? = null
        try {
            setContent {
                if (showContent) {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                        ArticleListPane(vm = currentVm, focused = true, onActivated = {}, modifier = Modifier.size(360.dp, 400.dp))
                    }
                }
            }
            waitForIdle()
            onRoot().performMouseInput { moveTo(center); repeat(12) { scroll(3f) } }
            waitForIdle()
            assertTrue("article-a39" !in renderedArticleIds(), "precondition: scrolled past the top row")

            // The process dies; meanwhile the tag was deleted elsewhere (a sync), so restoring falls back to All.
            val savedState = registry.performSave()
            showContent = false
            waitForIdle()
            first.stop()
            if (deleteTag) db.tagsQueries.softDelete(1L, 1L, "t1")
            second = newHomeViewModel(driver, db, settingsDir = settingsDir)
            assertEquals(if (deleteTag) ArticleFilter.All else ArticleFilter.Tag("t1"), second.vm.filter.value)
            assertEquals(0L, second.vm.filterSelection.value.generation)
            registry = SaveableStateRegistry(restoredValues = savedState, canBeSaved = { true })
            currentVm = second.vm
            // The new ViewModel's list is loaded before the first frame, so the restored offset has rows to land on.
            awaitCondition { second.vm.articles.value.isNotEmpty() }
            showContent = true
            waitForIdle()

            check(renderedArticleIds())
        } finally {
            second?.stop()
            driver.close()
        }
    }
}
