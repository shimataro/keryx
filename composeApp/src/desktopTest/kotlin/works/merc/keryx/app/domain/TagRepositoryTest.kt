package works.merc.keryx.app.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [SyncScheduler] fake that counts invocations. */
private class TagCountingSyncScheduler : SyncScheduler {
    var callCount = 0
        private set

    override fun scheduleSync() {
        callCount++
    }
}

class TagRepositoryTest {
    private fun newRepo(
        db: works.merc.keryx.app.data.local.db.KeryxDatabase,
        syncScheduler: SyncScheduler = SyncScheduler {},
        clock: Clock = Clock { 0L },
    ) = TagRepository(db, syncScheduler, clock, Dispatchers.Unconfined)

    @Test
    fun createTagCreatesNewTagAndSchedulesSync() {
        val (driver, db) = inMemoryDb()
        try {
            val scheduler = TagCountingSyncScheduler()
            val repo = newRepo(db, syncScheduler = scheduler, clock = Clock { 100L })

            val id = repo.createTag("Kotlin", "#ff0000")

            val row = db.tagsQueries.getById(id).executeAsOne()
            assertEquals("Kotlin", row.name)
            assertEquals("#ff0000", row.color)
            assertNull(row.deleted_at)
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun createTagReactivatesSoftDeletedTagWithSameNameInsteadOfDuplicating() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t1", "Kotlin", now = 10L, deletedAt = 20L)
            val repo = newRepo(db, clock = Clock { 100L })

            val id = repo.createTag("Kotlin")

            assertEquals("t1", id)
            val row = db.tagsQueries.getById("t1").executeAsOne()
            assertNull(row.deleted_at)
            assertEquals(1, db.tagsQueries.watchAll().executeAsList().size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun updateTagUpdatesNameAndColorAndPersists() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t1", "Old", now = 10L)
            val scheduler = TagCountingSyncScheduler()
            val repo = newRepo(db, syncScheduler = scheduler, clock = Clock { 200L })

            repo.updateTag("t1", "New", "#00ff00")

            val row = db.tagsQueries.getById("t1").executeAsOne()
            assertEquals("New", row.name)
            assertEquals("#00ff00", row.color)
            assertEquals(200L, row.updated_at)
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun updateTagOnMissingTagIsNoOp() {
        val (driver, db) = inMemoryDb()
        try {
            val scheduler = TagCountingSyncScheduler()
            val repo = newRepo(db, syncScheduler = scheduler)

            repo.updateTag("missing", "New", null)

            assertEquals(0, scheduler.callCount)
            assertNull(db.tagsQueries.getById("missing").executeAsOneOrNull())
        } finally {
            driver.close()
        }
    }

    @Test
    fun deleteTagSoftDeletesWithoutRemovingRow() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t1", "Kotlin", now = 10L)
            val scheduler = TagCountingSyncScheduler()
            val repo = newRepo(db, syncScheduler = scheduler, clock = Clock { 300L })

            repo.deleteTag("t1")

            val row = db.tagsQueries.getById("t1").executeAsOne()
            assertNotNull(row.deleted_at)
            assertEquals(300L, row.deleted_at)
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun setFeedTagAttachClearsDeletedAt() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertTag("t1", "Kotlin")
            db.insertFeedTag("f1", "t1", deletedAt = 5L)
            val scheduler = TagCountingSyncScheduler()
            val repo = newRepo(db, syncScheduler = scheduler, clock = Clock { 400L })

            repo.setFeedTag("f1", "t1", attached = true)

            val row = db.feed_tagsQueries.watchAllActive().executeAsList().single()
            assertEquals("f1", row.feed_id)
            assertEquals("t1", row.tag_id)
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun setFeedTagDetachSetsDeletedAt() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertTag("t1", "Kotlin")
            db.insertFeedTag("f1", "t1")
            val repo = newRepo(db, clock = Clock { 500L })

            repo.setFeedTag("f1", "t1", attached = false)

            assertTrue(db.feed_tagsQueries.watchAllActive().executeAsList().isEmpty())
        } finally {
            driver.close()
        }
    }

    @Test
    fun watchFeedTagMapGroupsByFeedAcrossMultipleFeedsAndTags() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertFeed("f2")
            db.insertTag("t1", "Tag1")
            db.insertTag("t2", "Tag2")
            db.insertFeedTag("f1", "t1")
            db.insertFeedTag("f1", "t2")
            db.insertFeedTag("f2", "t1")
            db.insertFeedTag("f2", "t2", deletedAt = 1L)

            val repo = newRepo(db)
            val map = repo.watchFeedTagMap().first()

            assertEquals(setOf("t1", "t2"), map["f1"])
            assertEquals(setOf("t1"), map["f2"])
        } finally {
            driver.close()
        }
    }

    @Test
    fun watchTagIdsForFeedReturnsOnlyThatFeedsTags() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertFeed("f2")
            db.insertTag("t1", "Tag1")
            db.insertTag("t2", "Tag2")
            db.insertFeedTag("f1", "t1")
            db.insertFeedTag("f2", "t2")

            val repo = newRepo(db)
            val ids = repo.watchTagIdsForFeed("f1").first()

            assertEquals(listOf("t1"), ids)
        } finally {
            driver.close()
        }
    }

    @Test
    fun getTagByIdReturnsExistingTag() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t1", "Kotlin")
            val repo = newRepo(db)

            val tag = repo.getTagById("t1")

            assertEquals("t1", tag?.id)
            assertEquals("Kotlin", tag?.name)
        } finally {
            driver.close()
        }
    }

    @Test
    fun getTagByIdReturnsSoftDeletedTagWithDeletedAtSet() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t1", "Kotlin", deletedAt = 20L)
            val repo = newRepo(db)

            val tag = repo.getTagById("t1")

            assertNotNull(tag)
            assertNotNull(tag.deleted_at)
        } finally {
            driver.close()
        }
    }

    @Test
    fun getTagByIdReturnsNullForMissingTag() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = newRepo(db)

            assertNull(repo.getTagById("missing"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun watchAllTagsExcludesSoftDeletedTags() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t1", "Active")
            db.insertTag("t2", "Deleted", deletedAt = 1L)

            val repo = newRepo(db)
            val names = repo.watchAllTags().first().map { it.name }

            assertEquals(listOf("Active"), names)
        } finally {
            driver.close()
        }
    }
}
