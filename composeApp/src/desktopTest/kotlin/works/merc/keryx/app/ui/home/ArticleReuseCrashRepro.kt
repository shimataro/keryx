package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.data.local.db.Articles
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.fail

/**
 * Deterministic reproduction of a **known, unfixed** crash — see `docs/known-issues.md`.
 *
 * Scrolling the article list while the selected article changes repeatedly kills the AWT event
 * thread with `IllegalArgumentException: onReuse is only expected on attached node`, thrown from
 * inside Compose's own lazy-list item recycling (`LayoutNode.onReuse` <- `Applier.reuse` <-
 * `LazyListState.onScroll`). It is a Compose Multiplatform defect, not a misuse of the API here,
 * and no workaround was found short of dropping scroll-into-view entirely.
 *
 * `performMouseInput { scroll(...) }` drives the same desktop path as a real wheel scroll
 * (`onMouseWheelEvent` -> `MouseWheelScrollingLogic` -> `LazyListState.onScroll` ->
 * `forceRemeasure`), so no manual interaction is needed. The selection change and the wheel event
 * must alternate at least 15 times; below that it does not reproduce at all, at 15 it reproduces
 * every run.
 *
 * **Disabled on purpose** so the suite stays green while the bug is outstanding. After a Compose
 * upgrade, drop the [Ignore] and run this a few times — if it passes repeatedly the upstream fix
 * has landed, and `docs/known-issues.md` can go.
 */
@OptIn(ExperimentalTestApi::class)
@Ignore("Known upstream Compose bug — see docs/known-issues.md")
class ArticleReuseCrashRepro {

    /** Comfortably past the measured threshold of 15. */
    private val cycles = 60

    /** Long enough that rows are pushed out of, and pulled back into, the reuse pool. */
    private val itemCount = 300

    @Test
    fun scrollingWhileSelectionChangesDoesNotCrash() {
        val failures = runScrollHammer()
        if (failures.isEmpty()) return
        fail("Reproduced (${failures.size}): ${failures.first().stackTraceToString()}")
    }

    /**
     * Hammers the list and returns everything that blew up.
     *
     * The crash surfaces on the AWT event thread, where Compose's own handler may swallow it rather
     * than letting it propagate into the test body, so an uncaught-exception handler is installed
     * for the duration and its captures are returned alongside anything thrown inline.
     */
    private fun runScrollHammer(): List<Throwable> {
        val captured = mutableListOf<Throwable>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> synchronized(captured) { captured += throwable } }
        try {
            runDesktopComposeUiTest {
                val items = List(itemCount) { reproArticle("a$it", publishedAt = it.toLong()) }
                lateinit var state: LazyListState
                var selected by mutableStateOf<Articles?>(null)

                setContent {
                    state = rememberLazyListState()
                    ArticleListPaneContent(
                        articles = items,
                        feedTitles = mapOf("f1" to "Some Feed With A Fairly Long Title"),
                        selected = selected,
                        unreadOnly = false,
                        onToggleUnreadOnly = {},
                        onToggleSort = {},
                        onMarkAllRead = {},
                        onSelectArticle = {},
                        modifier = Modifier.size(360.dp, 400.dp),
                        listState = state,
                    )
                }
                waitForIdle()

                repeat(cycles) { cycle ->
                    onRoot().performMouseInput { scroll(12f) }
                    waitForIdle()
                    onRoot().performMouseInput { scroll(-12f) }
                    waitForIdle()
                    // Stands in for key auto-repeat moving the selection under the scroll.
                    selected = items[(cycle * 7) % items.size]
                    waitForIdle()
                }
            }
        } catch (t: Throwable) {
            synchronized(captured) { captured += t }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        return synchronized(captured) { captured.toList() }
    }
}

private fun reproArticle(id: String, publishedAt: Long): Articles = Articles(
    id = id,
    feed_id = "f1",
    guid = "g$id",
    url = "u$id",
    title = "Article $id",
    summary = null,
    content = null,
    author = null,
    published_at = publishedAt,
    thumbnail_url = null,
    is_read = if (publishedAt % 2 == 0L) 1L else 0L,
    read_at = null,
    is_starred = if (publishedAt % 5 == 0L) 1L else 0L,
    starred_at = null,
    cached_at = 0L,
    search_text = "",
    updated_at = 0L,
    created_at = 0L,
    deleted_at = null,
    deleted_updated_at = null,
)
