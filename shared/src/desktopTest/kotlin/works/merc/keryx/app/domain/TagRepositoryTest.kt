package works.merc.keryx.app.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.SameNameInsertingDriver
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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

    /** A new tag's id is the one its name derives from, so devices converge on the same row. */
    @Test
    fun createTagAssignsTheNameDerivedId() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = newRepo(db, clock = Clock { 100L })

            val id = repo.createTag("Kotlin", "#ff0000")

            assertEquals(IdGenerator.tagId("Kotlin"), id)
            assertEquals(listOf(id), db.tagsQueries.watchAll().executeAsList().map { it.id })
        } finally {
            driver.close()
        }
    }

    /**
     * When the derived id is already held — a row renamed off the name keeps its id — the creation
     * falls back to the id source instead of colliding with that row.
     */
    @Test
    fun createTagFallsBackToTheIdSourceWhenTheDerivedIdIsTaken() {
        val (driver, db) = inMemoryDb()
        try {
            // A row renamed off the name keeps its id: it holds the id "Kotlin" would derive.
            db.tagsQueries.upsert(
                id = IdGenerator.tagId("Kotlin"), name = "Renamed away", color = null, sort_order = 0L,
                deleted_at = null, updated_at = 20L, created_at = 10L,
            )
            val repo = newRepo(db, clock = Clock { 100L })

            val id = repo.createTag("Kotlin", "#ff0000")

            assertNotEquals(IdGenerator.tagId("Kotlin"), id, "the derived id is held by the renamed row")
            assertEquals(listOf(id, IdGenerator.tagId("Kotlin")).sorted(), db.tagsQueries.watchAll().executeAsList().map { it.id }.sorted())
            assertEquals("Kotlin", db.tagsQueries.getById(id).executeAsOne().name)
            assertEquals("Renamed away", db.tagsQueries.getById(IdGenerator.tagId("Kotlin")).executeAsOne().name)
        } finally {
            driver.close()
        }
    }

    /**
     * A create, a delete and a recreation within the same millisecond still stamp strictly increasing
     * updated_at, so neither the list value nor the sync merge's strict last-write-wins can mistake the
     * recreated tag for the one before the delete.
     */
    @Test
    fun deleteAndRecreateInTheSameMillisecondStampStrictlyIncreasingTimes() {
        val (driver, db) = inMemoryDb()
        try {
            val repo = newRepo(db, clock = Clock { 100L })
            val id = repo.createTag("Kotlin")
            val created = db.tagsQueries.getById(id).executeAsOne().updated_at

            repo.deleteTag(id)
            val deleted = db.tagsQueries.getById(id).executeAsOne().updated_at
            repo.createTag("Kotlin")
            val recreated = db.tagsQueries.getById(id).executeAsOne().updated_at

            assertEquals(100L, created)
            assertTrue(deleted > created)
            assertTrue(recreated > deleted)
        } finally {
            driver.close()
        }
    }

    /**
     * An edit made right after a same-millisecond delete and recreation, whose stamps ran ahead of the
     * clock, is still stamped newest — otherwise a device that already has the recreated row would drop
     * the edit in the sync merge's strict last-write-wins.
     */
    @Test
    fun anEditRightAfterASameMillisecondRecreationIsStampedNewest() {
        val (driver, db) = inMemoryDb()
        try {
            var now = 100L
            val repo = newRepo(db, clock = Clock { now })
            val id = repo.createTag("Kotlin", "#ff0000")
            repo.deleteTag(id)
            repo.createTag("Kotlin")
            val recreated = db.tagsQueries.getById(id).executeAsOne().updated_at

            now = 101L
            repo.updateTag(id, "Kotlin", "#00ff00")

            val row = db.tagsQueries.getById(id).executeAsOne()
            assertTrue(row.updated_at > recreated)
            assertEquals("#00ff00", row.color)
        } finally {
            driver.close()
        }
    }

    @Test
    fun anEditWithTheClockAheadOfTheRowIsStampedWithTheCurrentTime() {
        val (driver, db) = inMemoryDb()
        try {
            var now = 100L
            val repo = newRepo(db, clock = Clock { now })
            val id = repo.createTag("Kotlin")

            now = 5_000L
            repo.updateTag(id, "Renamed", null)

            assertEquals(5_000L, db.tagsQueries.getById(id).executeAsOne().updated_at)
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
    fun createTagReactivatingSoftDeletedTagPreservesOriginalCreatedAt() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t1", "Kotlin", now = 5L, deletedAt = 20L)
            val repo = newRepo(db, clock = Clock { 100L })

            repo.createTag("Kotlin")

            val row = db.tagsQueries.getById("t1").executeAsOne()
            assertEquals(5L, row.created_at)
        } finally {
            driver.close()
        }
    }

    @Test
    fun createTagReactivatingWithNoColorArgumentPreservesExistingColor() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t1", "Kotlin", now = 10L, deletedAt = 20L, color = "#111111")
            val repo = newRepo(db, clock = Clock { 100L })

            repo.createTag("Kotlin") // no color argument

            val row = db.tagsQueries.getById("t1").executeAsOne()
            assertEquals("#111111", row.color)
        } finally {
            driver.close()
        }
    }

    @Test
    fun createTagReactivatingSoftDeletedTagPreservesExistingSortOrder() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t1", "Kotlin", now = 10L, deletedAt = 20L, sortOrder = 3L)
            val repo = newRepo(db, clock = Clock { 100L })

            repo.createTag("Kotlin")

            val row = db.tagsQueries.getById("t1").executeAsOne()
            assertEquals(3L, row.sort_order)
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
    fun getAllTagsReturnsLiveTagsInDisplayOrderExcludingSoftDeletedOnes() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t1", "Second", sortOrder = 1L)
            db.insertTag("t2", "First", sortOrder = 0L)
            db.insertTag("t3", "Deleted", sortOrder = 2L, deletedAt = 1L)
            val repo = newRepo(db)

            val tags = repo.getAllTags()

            assertEquals(listOf("First", "Second"), tags.map { it.name })
        } finally {
            driver.close()
        }
    }

    @Test
    fun getFeedTagMapGroupsAttachedTagIdsByFeedIgnoringDetachedLinks() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            db.insertFeed("f2")
            db.insertFeed("f3")
            db.insertTag("t1", "Kotlin")
            db.insertTag("t2", "News")
            db.insertFeedTag("f1", "t1")
            db.insertFeedTag("f1", "t2")
            db.insertFeedTag("f2", "t1")
            db.insertFeedTag("f3", "t1", deletedAt = 5L) // detached
            val repo = newRepo(db)

            val map = repo.getFeedTagMap()

            assertEquals(mapOf("f1" to setOf("t1", "t2"), "f2" to setOf("t1")), map)
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

    @Test
    fun createTagReactivatingWithAColorReplacesTheExistingColor() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t1", "Kotlin", now = 10L, deletedAt = 20L, sortOrder = 3L, color = "#000000")
            val repo = newRepo(db, clock = Clock { 100L })

            val id = repo.createTag("Kotlin", "#ff0000")

            assertEquals("t1", id)
            val row = db.tagsQueries.getById("t1").executeAsOne()
            assertEquals("#ff0000", row.color)
            assertEquals(3L, row.sort_order)
            assertNull(row.deleted_at)
        } finally {
            driver.close()
        }
    }

    // --- Names are not unique: the canonical row is reused, and a rename may cross any name ---

    @Test
    fun createTagReusesTheEarliestCreatedAliveRowOfTheName() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t-late", "Kotlin", now = 20L)
            db.insertTag("t-early", "Kotlin", now = 10L)
            val repo = newRepo(db, clock = Clock { 100L })

            val id = repo.createTag("Kotlin")

            assertEquals("t-early", id)
            assertEquals(2, db.tagsQueries.watchAll().executeAsList().size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun createTagPrefersAnAliveSameNamedRowOverAnEarlierDeletedOne() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t-deleted", "Kotlin", now = 5L, deletedAt = 6L)
            db.insertTag("t-alive", "Kotlin", now = 10L)
            val repo = newRepo(db, clock = Clock { 100L })

            val id = repo.createTag("Kotlin")

            assertEquals("t-alive", id)
            // The earlier deleted twin stays deleted; no row was created.
            assertNotNull(db.tagsQueries.getById("t-deleted").executeAsOne().deleted_at)
            assertEquals(listOf("t-alive"), db.tagsQueries.watchAll().executeAsList().map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun createTagOnOnlyDeletedSameNamedRowsReactivatesTheEarliest() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t-late", "Kotlin", now = 20L, deletedAt = 21L)
            db.insertTag("t-early", "Kotlin", now = 10L, deletedAt = 11L)
            val repo = newRepo(db, clock = Clock { 100L })

            val id = repo.createTag("Kotlin")

            assertEquals("t-early", id)
            assertNull(db.tagsQueries.getById("t-early").executeAsOneOrNull()?.deleted_at)
            // The later twin stays deleted.
            assertNotNull(db.tagsQueries.getById("t-late").executeAsOne().deleted_at)
        } finally {
            driver.close()
        }
    }

    @Test
    fun updateTagMayRenameOntoANameAnAliveRowHolds() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t1", "Old", now = 10L)
            db.insertTag("t2", "New", now = 10L)
            val repo = newRepo(db, clock = Clock { 200L })

            repo.updateTag("t1", "New", null)

            // No UNIQUE(name) blocks it: both rows keep their ids under the shared name, and the UI
            // numbers them.
            assertEquals("New", db.tagsQueries.getById("t1").executeAsOne().name)
            assertEquals("New", db.tagsQueries.getById("t2").executeAsOne().name)
        } finally {
            driver.close()
        }
    }

    @Test
    fun updateTagMayRenameOntoANameADeletedRowHolds() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertTag("t1", "Old", now = 10L)
            db.insertTag("t2", "New", now = 10L, deletedAt = 20L)
            val repo = newRepo(db, clock = Clock { 200L })

            repo.updateTag("t1", "New", null)

            assertEquals("New", db.tagsQueries.getById("t1").executeAsOne().name)
            assertNotNull(db.tagsQueries.getById("t2").executeAsOne().deleted_at)
        } finally {
            driver.close()
        }
    }

    // --- A same-named tag another writer inserts mid-call (a sync merge, a concurrent create) ---

    @Test
    fun createTagReusesASameNamedTagInsertedByAnotherWriterInsteadOfFailing() {
        val (base, _) = inMemoryDb()
        try {
            val driver = SameNameInsertingDriver(base, table = "tags", name = "Kotlin", otherId = "cloud-id")
            val db = KeryxDatabase(driver)
            val repo = newRepo(db, clock = Clock { 100L })

            val id = repo.createTag("Kotlin", "#ff0000")

            assertTrue(driver.fired)
            val rows = db.tagsQueries.watchAll().executeAsList()
            assertEquals(listOf(id), rows.map { it.id })
            assertEquals("Kotlin", rows.single().name)
        } finally {
            base.close()
        }
    }

    @Test
    fun createTagAndAttachReusesASameNamedTagInsertedByAnotherWriterInsteadOfFailing() {
        val (base, baseDb) = inMemoryDb()
        try {
            baseDb.insertFeed("f1")
            val driver = SameNameInsertingDriver(base, table = "tags", name = "Kotlin", otherId = "cloud-id")
            val db = KeryxDatabase(driver)
            val repo = newRepo(db, clock = Clock { 100L })

            val id = repo.createTagAndAttach("Kotlin", null, "f1")

            assertTrue(driver.fired)
            assertEquals(listOf(id), db.tagsQueries.watchAll().executeAsList().map { it.id })
            assertEquals(setOf(id), repo.getFeedTagMap()["f1"])
        } finally {
            base.close()
        }
    }

    // --- createTagAndAttach ---

    @Test
    fun createTagAndAttachCreatesTheTagAttachedToTheFeedAndSchedulesOneSync() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            val scheduler = TagCountingSyncScheduler()
            val repo = newRepo(db, syncScheduler = scheduler, clock = Clock { 100L })

            val id = repo.createTagAndAttach("Kotlin", "#ff0000", "f1")

            assertEquals("Kotlin", db.tagsQueries.getById(id).executeAsOne().name)
            assertEquals(mapOf("f1" to setOf(id)), repo.getFeedTagMap())
            assertEquals(1, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun createTagAndAttachLeavesNoTagBehindWhenTheAttachFails() {
        val (driver, db) = inMemoryDb()
        try {
            db.insertFeed("f1")
            driver.execute(
                null,
                "CREATE TRIGGER fail_attach BEFORE INSERT ON feed_tags BEGIN SELECT RAISE(ABORT, 'attach failed'); END",
                0,
            )
            val scheduler = TagCountingSyncScheduler()
            val repo = newRepo(db, syncScheduler = scheduler, clock = Clock { 100L })

            kotlin.test.assertFails { repo.createTagAndAttach("Kotlin", null, "f1") }

            assertNull(db.tagsQueries.getByName("Kotlin").executeAsOneOrNull())
            assertTrue(repo.getFeedTagMap().isEmpty())
            assertEquals(0, scheduler.callCount)
        } finally {
            driver.close()
        }
    }

    // --- per-field stamps (name_updated_at / color_updated_at / sort_order_updated_at) ---

    @Test
    fun aNewTagStampsEveryFieldWithItsCreationTime() {
        val (driver, db) = inMemoryDb()
        try {
            val id = newRepo(db, clock = Clock { 100L }).createTag("Kotlin", "#ff0000")

            val row = db.tagsQueries.getById(id).executeAsOne()
            assertEquals(listOf<Long?>(100L, 100L, 100L), listOf(row.name_updated_at, row.color_updated_at, row.sort_order_updated_at))
        } finally {
            driver.close()
        }
    }

    @Test
    fun anEditMovesOnlyTheStampsOfTheFieldsItChanges() {
        val (driver, db) = inMemoryDb()
        try {
            var now = 100L
            val repo = newRepo(db, clock = Clock { now })
            val id = repo.createTag("Kotlin", "#ff0000")

            now = 200L
            repo.updateTag(id, "Kotlin 2", "#ff0000") // name only
            var row = db.tagsQueries.getById(id).executeAsOne()
            assertEquals(listOf<Long?>(200L, 100L, 100L), listOf(row.name_updated_at, row.color_updated_at, row.sort_order_updated_at))

            now = 300L
            repo.updateTag(id, "Kotlin 2", null) // color only
            row = db.tagsQueries.getById(id).executeAsOne()
            assertEquals(listOf<Long?>(200L, 300L, 100L), listOf(row.name_updated_at, row.color_updated_at, row.sort_order_updated_at))

            now = 400L
            repo.updateTag(id, "Kotlin 2", null) // nothing changes: no field stamp moves
            row = db.tagsQueries.getById(id).executeAsOne()
            assertEquals(listOf<Long?>(200L, 300L, 100L), listOf(row.name_updated_at, row.color_updated_at, row.sort_order_updated_at))
            assertTrue(row.updated_at >= 400L)
        } finally {
            driver.close()
        }
    }

    /** A field stamp moves forward even when the clock is behind it (MAX(now, previous + 1)). */
    @Test
    fun aFieldStampNeverMovesBackwards() {
        val (driver, db) = inMemoryDb()
        try {
            var now = 500L
            val repo = newRepo(db, clock = Clock { now })
            val id = repo.createTag("Kotlin", null)

            now = 100L
            repo.updateTag(id, "Renamed", null)

            assertEquals(501L, db.tagsQueries.getById(id).executeAsOne().name_updated_at)
        } finally {
            driver.close()
        }
    }

    @Test
    fun reactivatingATagMovesTheColorStampOnlyWhenItChangesTheColor() {
        val (driver, db) = inMemoryDb()
        try {
            var now = 100L
            val repo = newRepo(db, clock = Clock { now })
            val id = repo.createTag("Kotlin", "#ff0000")
            now = 200L
            repo.deleteTag(id)

            now = 300L
            repo.createTag("Kotlin", null) // keeps the color
            var row = db.tagsQueries.getById(id).executeAsOne()
            assertEquals(100L, row.color_updated_at)
            assertEquals(100L, row.name_updated_at)

            now = 400L
            repo.deleteTag(id)
            now = 500L
            repo.createTag("Kotlin", "#00ff00")
            row = db.tagsQueries.getById(id).executeAsOne()
            assertEquals(500L, row.color_updated_at)
            assertEquals(100L, row.name_updated_at)
        } finally {
            driver.close()
        }
    }
}
