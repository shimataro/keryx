package works.merc.keryx.app.presentation.home

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedListExpansionTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: KeryxDatabase
    private val dir = FileIO.join(AppDirs.tempDir(), "feed-list-expansion-test-${Random.nextInt()}")

    @BeforeTest
    fun setUp() {
        val (d, database) = inMemoryDb()
        driver = d
        db = database
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        FileIO.delete(FileIO.join(dir, "local_settings.json"))
    }

    // Unconfined write dispatcher so every change is on disk by the time it is asserted.
    private fun settingsRepository() =
        SettingsRepository(db, LocalSettingsStore(dirOverride = dir), SyncScheduler {}, Clock { 0L }, writeDispatcher = Dispatchers.Unconfined)

    private fun persisted() = LocalSettingsStore(dirOverride = dir).load()

    @Test
    fun seedsFromLocalSettings() {
        settingsRepository().mutateLocalSettings {
            it.copy(collapsedFolderIds = setOf("d1"), expandedTagIds = setOf("t1"))
        }

        val expansion = FeedListExpansion(settingsRepository())

        assertEquals(setOf("d1"), expansion.collapsedFolderIds.value)
        assertEquals(setOf("t1"), expansion.expandedTagIds.value)
    }

    @Test
    fun toggleFolderFlipsAndPersists() {
        val expansion = FeedListExpansion(settingsRepository())

        expansion.toggleFolder("d1")
        assertEquals(setOf("d1"), expansion.collapsedFolderIds.value)
        assertEquals(setOf("d1"), persisted().collapsedFolderIds)

        expansion.toggleFolder("d1")
        assertEquals(emptySet(), expansion.collapsedFolderIds.value)
        assertEquals(emptySet(), persisted().collapsedFolderIds)
    }

    @Test
    fun toggleTagReturnsTheNewExpandedStateAndPersists() {
        val expansion = FeedListExpansion(settingsRepository())

        assertTrue(expansion.toggleTag("t1"))
        assertEquals(setOf("t1"), expansion.expandedTagIds.value)
        assertEquals(setOf("t1"), persisted().expandedTagIds)

        assertFalse(expansion.toggleTag("t1"))
        assertEquals(emptySet(), expansion.expandedTagIds.value)
        assertEquals(emptySet(), persisted().expandedTagIds)
    }

    @Test
    fun revealExpandsTheCollapsedFolderHidingAFeedRow() {
        db.insertFolder("d1", "Folder")
        db.insertFeed("f1", folderId = "d1")
        val feeds = listOf(db.feedsQueries.getById("f1").executeAsOne())
        val expansion = FeedListExpansion(settingsRepository())
        expansion.toggleFolder("d1")

        expansion.reveal(FeedListRowSelection.FeedInFolderGroup("f1"), feeds)

        assertEquals(emptySet(), expansion.collapsedFolderIds.value)
        assertEquals(emptySet(), persisted().collapsedFolderIds)
    }

    @Test
    fun revealExpandsTheCollapsedTagHidingANestedFeedRow() {
        val expansion = FeedListExpansion(settingsRepository())

        expansion.reveal(FeedListRowSelection.FeedInTag(feedId = "f1", tagId = "t1"), emptyList())

        assertEquals(setOf("t1"), expansion.expandedTagIds.value)
        assertEquals(setOf("t1"), persisted().expandedTagIds)
    }

    @Test
    fun forgetDropsDeletedFolderAndTag() {
        settingsRepository().mutateLocalSettings {
            it.copy(collapsedFolderIds = setOf("d1", "d2"), expandedTagIds = setOf("t1", "t2"))
        }
        val expansion = FeedListExpansion(settingsRepository())

        expansion.forgetFolder("d1")
        expansion.forgetTag("t1")

        assertEquals(setOf("d2"), expansion.collapsedFolderIds.value)
        assertEquals(setOf("t2"), expansion.expandedTagIds.value)
        assertEquals(setOf("d2"), persisted().collapsedFolderIds)
        assertEquals(setOf("t2"), persisted().expandedTagIds)
    }

    /**
     * Revealing a row whose tag is already expanded writes nothing: a stand-in write made behind
     * the expansion's back (by a second repository) survives the call.
     */
    @Test
    fun revealOfARowInAnAlreadyExpandedTagWritesNothing() {
        settingsRepository().mutateLocalSettings { it.copy(expandedTagIds = setOf("t1")) }
        val expansion = FeedListExpansion(settingsRepository())
        settingsRepository().mutateLocalSettings { it.copy(expandedTagIds = setOf("sentinel")) }

        expansion.reveal(FeedListRowSelection.FeedInTag(feedId = "f1", tagId = "t1"), emptyList())

        assertEquals(setOf("t1"), expansion.expandedTagIds.value)
        assertEquals(setOf("sentinel"), persisted().expandedTagIds)
    }

    @Test
    fun revealOfAnUnknownFeedChangesNothing() {
        db.insertFolder("d1", "Folder")
        db.insertFeed("f1", folderId = "d1")
        val feeds = listOf(db.feedsQueries.getById("f1").executeAsOne())
        val expansion = FeedListExpansion(settingsRepository())
        expansion.toggleFolder("d1")

        expansion.reveal(FeedListRowSelection.FeedInFolderGroup("unknown"), feeds)

        assertEquals(setOf("d1"), expansion.collapsedFolderIds.value)
        assertEquals(setOf("d1"), persisted().collapsedFolderIds)
    }

    @Test
    fun forgetFolderAndForgetTagAreIndependent() {
        settingsRepository().mutateLocalSettings {
            it.copy(collapsedFolderIds = setOf("x"), expandedTagIds = setOf("x"))
        }
        val expansion = FeedListExpansion(settingsRepository())

        expansion.forgetFolder("x")
        assertEquals(emptySet(), expansion.collapsedFolderIds.value)
        assertEquals(setOf("x"), expansion.expandedTagIds.value)

        expansion.forgetTag("x")
        assertEquals(emptySet(), expansion.expandedTagIds.value)
        assertEquals(emptySet(), persisted().collapsedFolderIds)
        assertEquals(emptySet(), persisted().expandedTagIds)
    }
}
