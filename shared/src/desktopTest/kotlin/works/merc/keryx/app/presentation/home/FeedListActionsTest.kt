package works.merc.keryx.app.presentation.home

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.InMemoryFaviconMissStore
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.domain.TagRepository
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedListActionsTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: KeryxDatabase
    private lateinit var expansion: FeedListExpansion
    private val dir = FileIO.join(AppDirs.tempDir(), "feed-list-actions-test-${Random.nextInt()}")

    private val selectedRow = MutableStateFlow<FeedListRowSelection>(FeedListRowSelection.All)
    private var filter: ArticleFilter = ArticleFilter.All
    private val selectCalls = mutableListOf<ArticleFilter>()

    // Writes run inline unless [holdWrites] is set — then they queue in [pendingWrites] until
    // [runPendingWrites], standing in for the ViewModel's serial dbWriteDispatcher.
    private var holdWrites = false
    private val pendingWrites = mutableListOf<suspend () -> Unit>()

    private fun runPendingWrites() = runBlocking {
        val writes = pendingWrites.toList()
        pendingWrites.clear()
        writes.forEach { it() }
    }
    private lateinit var actions: FeedListActions

    private fun failingClient() = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout)
    }

    @BeforeTest
    fun setUp() {
        val (d, database) = inMemoryDb()
        driver = d
        db = database
        val scheduler = SyncScheduler {}
        val clock = Clock { 100L }
        val articleRepository = ArticleRepository(db, FtsSearch(driver), scheduler, clock, Dispatchers.Unconfined)
        val feedRepository = FeedRepository(
            db, FeedFetcher(failingClient()), FaviconResolver(failingClient()),
            articleRepository, ftsManagerIndexed(driver), scheduler, NotificationCenter(),
            clock, Dispatchers.Unconfined,
            faviconMisses = InMemoryFaviconMissStore(),
        )
        val settings = SettingsRepository(
            db, LocalSettingsStore(dirOverride = dir), scheduler, clock, writeDispatcher = Dispatchers.Unconfined,
        )
        expansion = FeedListExpansion(settings)
        actions = FeedListActions(
            feedRepository,
            TagRepository(db, scheduler, clock, Dispatchers.Unconfined),
            FolderRepository(db, feedRepository, scheduler, clock, Dispatchers.Unconfined),
            expansion,
            selectedRow,
            currentFilter = { filter },
            selectFilter = { filter = it; selectCalls += it },
            launchWrite = { write -> if (holdWrites) pendingWrites += write else runBlocking { write() } },
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        FileIO.delete(FileIO.join(dir, "local_settings.json"))
    }

    // --- Blank names ---

    @Test
    fun createTagRejectsBlankNamesAndTrimsValidOnes() {
        actions.createTag("", null)
        actions.createTag("   ", "#fff")
        assertTrue(db.tagsQueries.watchAll().executeAsList().isEmpty())

        actions.createTag("  News  ", "#fff")
        val row = db.tagsQueries.watchAll().executeAsList().single()
        assertEquals("News", row.name)
        assertEquals("#fff", row.color)
    }

    @Test
    fun updateTagIgnoresBlankNamesAndTrimsValidOnes() {
        db.insertTag("t1", "Old")

        actions.updateTag("t1", "  ", null)
        assertEquals("Old", db.tagsQueries.getById("t1").executeAsOne().name)

        actions.updateTag("t1", " New ", null)
        assertEquals("New", db.tagsQueries.getById("t1").executeAsOne().name)
    }

    @Test
    fun createFolderRejectsBlankNamesAndTrimsValidOnes() {
        actions.createFolder("")
        actions.createFolder("  \t")
        assertTrue(db.foldersQueries.watchAll().executeAsList().isEmpty())

        actions.createFolder("  Tech  ")
        assertEquals("Tech", db.foldersQueries.watchAll().executeAsList().single().name)
    }

    @Test
    fun updateFolderIgnoresBlankNamesAndTrimsValidOnes() {
        db.insertFolder("d1", "Old")

        actions.updateFolder("d1", " ")
        assertEquals("Old", db.foldersQueries.getById("d1").executeAsOne().name)

        actions.updateFolder("d1", " New ")
        assertEquals("New", db.foldersQueries.getById("d1").executeAsOne().name)
    }

    // --- Deleting the selected item ---

    @Test
    fun deletingTheSelectedTagSelectsAll() {
        db.insertTag("t1", "T")
        filter = ArticleFilter.Tag("t1")

        actions.deleteTag("t1")

        assertEquals(listOf<ArticleFilter>(ArticleFilter.All), selectCalls)
        assertNotNull(db.tagsQueries.getById("t1").executeAsOne().deleted_at)
    }

    @Test
    fun deletingAnotherTagKeepsTheSelection() {
        db.insertTag("t1", "T")
        db.insertTag("t2", "U")
        filter = ArticleFilter.Tag("t2")

        actions.deleteTag("t1")

        assertTrue(selectCalls.isEmpty())
        assertNotNull(db.tagsQueries.getById("t1").executeAsOne().deleted_at)
    }

    @Test
    fun deletingTheSelectedFolderSelectsAll() {
        db.insertFolder("d1", "D")
        filter = ArticleFilter.Folder("d1")

        actions.deleteFolder("d1")

        assertEquals(listOf<ArticleFilter>(ArticleFilter.All), selectCalls)
        assertNotNull(db.foldersQueries.getById("d1").executeAsOne().deleted_at)
    }

    @Test
    fun deletingAnotherFolderKeepsTheSelection() {
        db.insertFolder("d1", "D")
        db.insertFolder("d2", "E")
        filter = ArticleFilter.Folder("d2")

        actions.deleteFolder("d1")

        assertTrue(selectCalls.isEmpty())
    }

    @Test
    fun deletingAFolderForgetsItsCollapsedState() {
        db.insertFolder("d1", "D")
        expansion.toggleFolder("d1")
        assertEquals(setOf("d1"), expansion.collapsedFolderIds.value)

        actions.deleteFolder("d1")

        assertEquals(emptySet(), expansion.collapsedFolderIds.value)
    }

    // --- Tag-nested row selection ---

    @Test
    fun deletingATagMovesItsNestedFeedRowSelectionToTheFolderGroupRow() {
        db.insertTag("t1", "T")
        selectedRow.value = FeedListRowSelection.FeedInTag("f1", "t1")

        actions.deleteTag("t1")

        assertEquals(FeedListRowSelection.FeedInFolderGroup("f1"), selectedRow.value)
    }

    @Test
    fun deletingATagLeavesAFeedRowNestedUnderAnotherTagAlone() {
        db.insertTag("t1", "T")
        selectedRow.value = FeedListRowSelection.FeedInTag("f1", "t2")

        actions.deleteTag("t1")

        assertEquals(FeedListRowSelection.FeedInTag("f1", "t2"), selectedRow.value)
    }

    @Test
    fun deletingATagForgetsItsExpandedState() {
        db.insertTag("t1", "T")
        actions.toggleTagExpanded("t1")
        assertEquals(setOf("t1"), expansion.expandedTagIds.value)

        actions.deleteTag("t1")

        assertEquals(emptySet(), expansion.expandedTagIds.value)
    }

    @Test
    fun collapsingATagMovesItsNestedFeedRowSelectionToTheFolderGroupRow() {
        actions.toggleTagExpanded("t1") // expand
        selectedRow.value = FeedListRowSelection.FeedInTag("f1", "t1")

        actions.toggleTagExpanded("t1") // collapse

        assertEquals(setOf<String>(), expansion.expandedTagIds.value)
        assertEquals(FeedListRowSelection.FeedInFolderGroup("f1"), selectedRow.value)
    }

    @Test
    fun expandingATagLeavesTheRowSelectionAlone() {
        selectedRow.value = FeedListRowSelection.FeedInTag("f1", "t1")

        actions.toggleTagExpanded("t1") // expand

        assertEquals(setOf("t1"), expansion.expandedTagIds.value)
        assertEquals(FeedListRowSelection.FeedInTag("f1", "t1"), selectedRow.value)
    }

    @Test
    fun collapsingATagLeavesOtherRowSelectionsAlone() {
        actions.toggleTagExpanded("t1")
        selectedRow.value = FeedListRowSelection.Tag("t1")

        actions.toggleTagExpanded("t1") // collapse

        assertEquals(FeedListRowSelection.Tag("t1"), selectedRow.value)
    }

    // --- Repository delegation ---

    @Test
    fun setFeedTagAttachesAndDetaches() {
        db.insertFeed("f1")
        db.insertTag("t1", "T")

        actions.setFeedTag("f1", "t1", attached = true)
        assertEquals(mapOf("f1" to setOf("t1")), feedTagMap())

        actions.setFeedTag("f1", "t1", attached = false)
        assertEquals(emptyMap(), feedTagMap())
    }

    @Test
    fun moveFeedPutsTheFeedInTheFolder() {
        db.insertFolder("d1", "D")
        db.insertFeed("f1")

        actions.moveFeed("f1", "d1", null)

        assertEquals("d1", db.feedsQueries.getById("f1").executeAsOne().folder_id)
    }

    @Test
    fun moveFeedBeforeATargetOrdersItAheadOfIt() {
        db.insertFeed("f1", sortOrder = 0)
        db.insertFeed("f2", sortOrder = 1)

        actions.moveFeed("f2", null, "f1")

        assertEquals(listOf("f2", "f1"), db.feedsQueries.getByFolder(null).executeAsList().map { it.id })
    }

    @Test
    fun reorderFoldersMovesTheDraggedFolderBeforeTheTarget() {
        db.insertFolder("d1", "A", sortOrder = 0)
        db.insertFolder("d2", "B", sortOrder = 1)

        actions.reorderFolders("d2", "d1")

        assertEquals(listOf("d2", "d1"), db.foldersQueries.watchAll().executeAsList().map { it.id })
    }

    // --- Create-and-apply ---

    @Test
    fun createTagWithAFeedAttachesTheNewTagToIt() {
        db.insertFeed("f1")

        actions.createTag("News", null, attachToFeedId = "f1")

        val tagId = db.tagsQueries.watchAll().executeAsList().single().id
        assertEquals(mapOf("f1" to setOf(tagId)), feedTagMap())
    }

    @Test
    fun createFolderWithAFeedMovesItIntoTheNewFolder() {
        db.insertFeed("f1")

        actions.createFolder("Tech", moveFeedId = "f1")

        val folderId = db.foldersQueries.watchAll().executeAsList().single().id
        assertEquals(folderId, db.feedsQueries.getById("f1").executeAsOne().folder_id)
    }

    // --- Writes go through launchWrite ---

    @Test
    fun everyActionReturnsBeforeItsWriteRunsAndUpdatesUiStateAtOnce() {
        db.insertFeed("f1", sortOrder = 0)
        db.insertFeed("f2", sortOrder = 1)
        db.insertTag("t1", "Old tag")
        db.insertTag("t2", "Doomed tag")
        db.insertFolder("d1", "Old folder", sortOrder = 0)
        db.insertFolder("d2", "Second folder", sortOrder = 1)
        db.insertFolder("d3", "Doomed folder", sortOrder = 2)
        actions.toggleTagExpanded("t2")
        expansion.toggleFolder("d3")
        selectedRow.value = FeedListRowSelection.FeedInTag("f1", "t2")
        filter = ArticleFilter.Folder("d3")
        holdWrites = true

        actions.createTag("New", null, attachToFeedId = "f2")
        actions.updateTag("t1", "Renamed tag", null)
        actions.deleteTag("t2")
        actions.setFeedTag("f1", "t1", attached = true)
        actions.createFolder("Fresh", moveFeedId = "f1")
        actions.updateFolder("d1", "Renamed folder")
        actions.deleteFolder("d3")
        actions.moveFeed("f2", "d1", null)
        actions.reorderFolders("d2", "d1")

        // UI-side state changed at once…
        assertEquals(FeedListRowSelection.FeedInFolderGroup("f1"), selectedRow.value)
        assertEquals(emptySet(), expansion.expandedTagIds.value)
        assertEquals(emptySet(), expansion.collapsedFolderIds.value)
        assertEquals(listOf<ArticleFilter>(ArticleFilter.All), selectCalls)
        // …while the DB is untouched until the queued writes run.
        assertEquals(9, pendingWrites.size)
        assertEquals(listOf("Doomed tag", "Old tag"), db.tagsQueries.watchAll().executeAsList().map { it.name }.sorted())
        assertEquals(emptyMap(), feedTagMap())
        assertEquals(listOf("d1", "d2", "d3"), db.foldersQueries.watchAll().executeAsList().map { it.id })
        assertEquals("Old folder", db.foldersQueries.getById("d1").executeAsOne().name)
        assertNull(db.feedsQueries.getById("f1").executeAsOne().folder_id)
        assertNull(db.feedsQueries.getById("f2").executeAsOne().folder_id)

        runPendingWrites()

        val newTagId = db.tagsQueries.watchAll().executeAsList().single { it.name == "New" }.id
        assertEquals("Renamed tag", db.tagsQueries.getById("t1").executeAsOne().name)
        assertNotNull(db.tagsQueries.getById("t2").executeAsOne().deleted_at)
        assertEquals(mapOf("f1" to setOf("t1"), "f2" to setOf(newTagId)), feedTagMap())
        val freshId = db.foldersQueries.watchAll().executeAsList().single { it.name == "Fresh" }.id
        assertEquals(freshId, db.feedsQueries.getById("f1").executeAsOne().folder_id)
        assertEquals("Renamed folder", db.foldersQueries.getById("d1").executeAsOne().name)
        assertNotNull(db.foldersQueries.getById("d3").executeAsOne().deleted_at)
        assertEquals("d1", db.feedsQueries.getById("f2").executeAsOne().folder_id)
        assertEquals(listOf("d2", "d1"), db.foldersQueries.watchAll().executeAsList().map { it.id }.filter { it != freshId })
    }

    private fun feedTagMap(): Map<String, Set<String>> =
        db.feed_tagsQueries.watchAllActive().executeAsList()
            .groupBy({ it.feed_id }, { it.tag_id }).mapValues { it.value.toSet() }
}
