package works.merc.keryx.app.presentation.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.data.local.db.Feeds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshingIndicatorTest {

    private val delayMs = 400L

    /** Collects [source].membersHeldFor into the returned list of emissions, in the test's background scope. */
    private fun kotlinx.coroutines.test.TestScope.collectShown(
        source: MutableStateFlow<Set<String>>,
    ): MutableList<Set<String>> {
        val emissions = mutableListOf<Set<String>>()
        backgroundScope.launch { source.membersHeldFor(delayMs).collect { emissions += it } }
        return emissions
    }

    @Test
    fun emitsTheEmptySetFirst() = runTest {
        val emissions = collectShown(MutableStateFlow(emptySet()))
        runCurrent()
        assertEquals(listOf(emptySet()), emissions)
    }

    @Test
    fun aMemberPresentForLessThanTheDelayNeverAppears() = runTest {
        val source = MutableStateFlow(emptySet<String>())
        val emissions = collectShown(source)
        runCurrent()

        source.value = setOf("a")
        runCurrent()
        advanceTimeBy(delayMs - 1)
        source.value = emptySet()
        runCurrent()
        advanceTimeBy(delayMs * 3)

        assertTrue(emissions.all { it.isEmpty() }, "a short-lived member must never be shown: $emissions")
    }

    @Test
    fun aMemberPresentForTheDelayAppears() = runTest {
        val source = MutableStateFlow(emptySet<String>())
        val emissions = collectShown(source)
        runCurrent()

        source.value = setOf("a")
        runCurrent()
        advanceTimeBy(delayMs - 1)
        assertEquals(emptySet(), emissions.last())
        advanceTimeBy(1)
        runCurrent()

        assertEquals(setOf("a"), emissions.last())
    }

    @Test
    fun aMemberAlreadyPresentWhenCollectionStartsIsHeldForTheDelayToo() = runTest {
        val emissions = collectShown(MutableStateFlow(setOf("a")))
        runCurrent()
        assertEquals(emptySet(), emissions.last())
        advanceTimeBy(delayMs)
        runCurrent()
        assertEquals(setOf("a"), emissions.last())
    }

    @Test
    fun removalDropsAShownMemberImmediately() = runTest {
        val source = MutableStateFlow(emptySet<String>())
        val emissions = collectShown(source)
        source.value = setOf("a")
        runCurrent()
        advanceTimeBy(delayMs)
        runCurrent()
        assertEquals(setOf("a"), emissions.last())

        source.value = emptySet()
        runCurrent() // no virtual time advanced: the drop must not wait
        assertEquals(emptySet(), emissions.last())
    }

    @Test
    fun reEntryRestartsTheWait() = runTest {
        val source = MutableStateFlow(emptySet<String>())
        val emissions = collectShown(source)
        runCurrent()

        source.value = setOf("a")
        runCurrent()
        advanceTimeBy(delayMs - 100)
        source.value = emptySet()
        runCurrent()
        source.value = setOf("a")
        runCurrent()
        // Had the first wait survived, "a" would appear 100ms from here.
        advanceTimeBy(delayMs - 1)
        assertTrue(emissions.all { it.isEmpty() }, "re-entry must restart the wait: $emissions")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(setOf("a"), emissions.last())
    }

    @Test
    fun membersAreTimedIndependentlyAndASurvivorStaysShownWhenAnotherLeaves() = runTest {
        val source = MutableStateFlow(emptySet<String>())
        val emissions = collectShown(source)
        runCurrent()

        source.value = setOf("a")
        runCurrent()
        advanceTimeBy(200)
        source.value = setOf("a", "b")
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(setOf("a"), emissions.last())

        advanceTimeBy(200)
        runCurrent()
        assertEquals(setOf("a", "b"), emissions.last())

        source.value = setOf("b")
        runCurrent()
        assertEquals(setOf("b"), emissions.last())
    }

    // --- collapsedFoldersRefreshing ---

    @Test
    fun onlyCollapsedFoldersHoldingARefreshingFeedAreReturned() {
        val feeds = listOf(feed("f1", "folderA"), feed("f2", "folderB"), feed("f3", "folderC"))
        val result = collapsedFoldersRefreshing(
            feeds = feeds,
            refreshingFeedIds = setOf("f1", "f2"),
            collapsedFolderIds = setOf("folderA", "folderC"),
        )
        // folderB is expanded; folderC is collapsed but nothing in it refreshes.
        assertEquals(setOf("folderA"), result)
    }

    @Test
    fun folderlessRefreshingFeedsAreExcluded() {
        val result = collapsedFoldersRefreshing(
            feeds = listOf(feed("f1", null)),
            refreshingFeedIds = setOf("f1"),
            collapsedFolderIds = setOf("folderA"),
        )
        assertEquals(emptySet(), result)
    }

    @Test
    fun noRefreshingFeedsOrNoCollapsedFoldersYieldsNothing() {
        val feeds = listOf(feed("f1", "folderA"))
        assertEquals(emptySet(), collapsedFoldersRefreshing(feeds, emptySet(), setOf("folderA")))
        assertEquals(emptySet(), collapsedFoldersRefreshing(feeds, setOf("f1"), emptySet()))
    }

    @Test
    fun aRefreshingIdWithoutAMatchingFeedIsIgnored() {
        val result = collapsedFoldersRefreshing(listOf(feed("f1", "folderA")), setOf("ghost"), setOf("folderA"))
        assertEquals(emptySet(), result)
    }

    @Test
    fun severalRefreshingFeedsInOneFolderYieldItOnce() {
        val result = collapsedFoldersRefreshing(
            feeds = listOf(feed("f1", "folderA"), feed("f2", "folderA")),
            refreshingFeedIds = setOf("f1", "f2"),
            collapsedFolderIds = setOf("folderA"),
        )
        assertEquals(setOf("folderA"), result)
    }
}

private fun feed(id: String, folderId: String?): Feeds = Feeds(
    id = id,
    url = "https://example.com/$id",
    site_url = null,
    title = "Feed $id",
    description = null,
    favicon_url = null,
    etag = null,
    last_modified = null,
    error_count = 0L,
    last_error = null,
    custom_title = null,
    folder_id = folderId,
    deleted_at = null,
    updated_at = 0L,
    created_at = 0L,
    sort_order = 0L,
    folder_updated_at = null,
    sort_order_updated_at = null,
    custom_title_updated_at = null,
    deleted_updated_at = null,
)
