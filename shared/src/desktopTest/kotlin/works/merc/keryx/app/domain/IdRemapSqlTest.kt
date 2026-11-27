package works.merc.keryx.app.domain

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.inMemoryDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `ks_id_remap`'s chain folding ([IdRemapSql]) and its publication ([IdRemaps]). */
class IdRemapSqlTest {

    private fun SqlDriver.remapRows(table: String = "main.ks_id_remap"): Set<Triple<String, String, String>> =
        executeQuery(
            null,
            "SELECT kind, old_id, new_id FROM $table",
            { cursor ->
                QueryResult.Value(
                    buildSet {
                        while (cursor.next().value) add(Triple(cursor.getString(0)!!, cursor.getString(1)!!, cursor.getString(2)!!))
                    },
                )
            },
            0,
        ).value

    @Test
    fun recordingAMoveAddsItsEntry() {
        val (driver, _) = inMemoryDb()
        try {
            IdRemapSql.record(driver, ID_REMAP_KIND_TAG, "a", "b")

            assertEquals(setOf(Triple(ID_REMAP_KIND_TAG, "a", "b")), driver.remapRows())
        } finally {
            driver.close()
        }
    }

    /** A move of an id that was itself a move's target extends that chain: every old id maps one hop to the end. */
    @Test
    fun aMoveOfAPreviousTargetFoldsTheChain() {
        val (driver, _) = inMemoryDb()
        try {
            IdRemapSql.record(driver, ID_REMAP_KIND_FOLDER, "a", "b")
            IdRemapSql.record(driver, ID_REMAP_KIND_FOLDER, "b", "c")
            IdRemapSql.record(driver, ID_REMAP_KIND_FOLDER, "c", "d")

            assertEquals(
                setOf(
                    Triple(ID_REMAP_KIND_FOLDER, "a", "d"),
                    Triple(ID_REMAP_KIND_FOLDER, "b", "d"),
                    Triple(ID_REMAP_KIND_FOLDER, "c", "d"),
                ),
                driver.remapRows(),
            )
        } finally {
            driver.close()
        }
    }

    /** Moving back onto a moved id leaves no cycle and no self-mapping: the id is live again. */
    @Test
    fun aMoveBackDropsTheEntryOfTheIdThatIsLiveAgain() {
        val (driver, _) = inMemoryDb()
        try {
            IdRemapSql.record(driver, ID_REMAP_KIND_TAG, "x", "a")
            IdRemapSql.record(driver, ID_REMAP_KIND_TAG, "a", "b")
            IdRemapSql.record(driver, ID_REMAP_KIND_TAG, "b", "a")

            assertEquals(
                setOf(Triple(ID_REMAP_KIND_TAG, "x", "a"), Triple(ID_REMAP_KIND_TAG, "b", "a")),
                driver.remapRows(),
            )
        } finally {
            driver.close()
        }
    }

    /** Kinds never fold into each other, and a move onto itself records nothing. */
    @Test
    fun kindsAreIndependentAndASelfMoveIsIgnored() {
        val (driver, _) = inMemoryDb()
        try {
            IdRemapSql.record(driver, ID_REMAP_KIND_TAG, "a", "b")
            IdRemapSql.record(driver, ID_REMAP_KIND_FOLDER, "b", "c")
            IdRemapSql.record(driver, ID_REMAP_KIND_FOLDER, "z", "z")

            assertEquals(
                setOf(Triple(ID_REMAP_KIND_TAG, "a", "b"), Triple(ID_REMAP_KIND_FOLDER, "b", "c")),
                driver.remapRows(),
            )
        } finally {
            driver.close()
        }
    }

    /**
     * The set-wise statements a merge runs over a table of moves fold the batch into the existing
     * entries, and touch only `main` — never a same-named table in an attached `cloud` file.
     */
    @Test
    fun aBatchFromATableFoldsIntoMainOnly() {
        val (driver, _) = inMemoryDb()
        try {
            IdRemapSql.record(driver, ID_REMAP_KIND_TAG, "x", "a")
            driver.execute(null, "ATTACH DATABASE ':memory:' AS cloud", 0)
            driver.execute(null, "CREATE TABLE cloud.ks_id_remap (kind TEXT NOT NULL, old_id TEXT NOT NULL, new_id TEXT NOT NULL, PRIMARY KEY (kind, old_id))", 0)
            driver.execute(null, "INSERT INTO cloud.ks_id_remap VALUES ('tag', 'a', 'q')", 0)
            driver.execute(null, "CREATE TEMP TABLE batch (kind TEXT, old_id TEXT, new_id TEXT)", 0)
            driver.execute(null, "INSERT INTO batch VALUES ('tag', 'a', 'c'), ('folder', 'd', 'e')", 0)

            for (sql in IdRemapSql.foldStatements("SELECT kind, old_id, new_id FROM temp.batch")) {
                driver.execute(null, sql, 0)
            }

            assertEquals(
                setOf(
                    Triple(ID_REMAP_KIND_TAG, "x", "c"),
                    Triple(ID_REMAP_KIND_TAG, "a", "c"),
                    Triple(ID_REMAP_KIND_FOLDER, "d", "e"),
                ),
                driver.remapRows(),
            )
            assertEquals(setOf(Triple("tag", "a", "q")), driver.remapRows("cloud.ks_id_remap"))
        } finally {
            driver.close()
        }
    }

    // --- IdRemaps ---

    /**
     * The article re-keys share the table, but only the tag, folder and feed ones are loaded: the
     * article ids held outside the database are resolved one at a time instead (articles.resolveId).
     */
    @Test
    fun loadingTheRemapReadsTheTagFolderAndFeedRowsButNotTheArticleOnes() {
        val (driver, db) = inMemoryDb()
        try {
            IdRemapSql.record(driver, ID_REMAP_KIND_TAG, "t-old", "t-new")
            IdRemapSql.record(driver, ID_REMAP_KIND_FOLDER, "d-old", "d-new")
            IdRemapSql.record(driver, ID_REMAP_KIND_FEED, "f-old", "f-new")
            IdRemapSql.record(driver, ID_REMAP_KIND_ARTICLE, "a-old", "a-new")

            assertEquals(
                setOf(ID_REMAP_KIND_TAG, ID_REMAP_KIND_FOLDER, ID_REMAP_KIND_FEED),
                db.sync_bookkeepingQueries.idRemapPublished().executeAsList().map { it.kind }.toSet(),
            )
            val remap = loadIdRemap(db)
            assertEquals(mapOf("t-old" to "t-new"), remap.tagIds)
            assertEquals(mapOf("d-old" to "d-new"), remap.folderIds)
            assertEquals(mapOf("f-old" to "f-new"), remap.feedIds)
        } finally {
            driver.close()
        }
    }

    @Test
    fun theRemapMovesAFeedFilterAndFeedIdsButLeavesOtherFiltersAlone() {
        val remap = IdRemap(tagIds = emptyMap(), folderIds = emptyMap(), feedIds = mapOf("f-old" to "f-new", "f-other" to "f-new"))
        assertEquals(ArticleFilter.Feed("f-new"), remap.filter(ArticleFilter.Feed("f-old")))
        val untouched = ArticleFilter.Feed("f-live")
        assertSame(untouched, remap.filter(untouched))
        assertEquals(ArticleFilter.All, remap.filter(ArticleFilter.All))
        assertEquals(ArticleFilter.Tag("f-old"), remap.filter(ArticleFilter.Tag("f-old")))
        // Two ids of one feed become one.
        assertEquals(setOf("f-new", "f-live"), remap.feeds(setOf("f-old", "f-other", "f-live")))
        assertEquals(false, remap.isEmpty)
    }

    @Test
    fun remapsLoadTheTableInTheBackgroundAndReadItAgainOnEachPublish() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            val remaps = IdRemaps(db, Dispatchers.Unconfined)
            assertSame(IdRemap.EMPTY, remaps.current.value)
            assertTrue(remaps.current.value.isEmpty)

            IdRemapSql.record(driver, ID_REMAP_KIND_TAG, "t-old", "t-new")
            IdRemapSql.record(driver, ID_REMAP_KIND_FOLDER, "d-old", "d-new")
            // Nothing is published before publishFromDb: a merge publishes only once it has committed.
            assertTrue(remaps.current.value.isEmpty)

            remaps.publishFromDb()

            val remap = remaps.current.value
            assertEquals(mapOf("t-old" to "t-new"), remap.tagIds)
            assertEquals(mapOf("d-old" to "d-new"), remap.folderIds)
            assertEquals(1, IdRemaps(db, Dispatchers.Unconfined).current.value.tagIds.size, "a new instance reads the same table")
        } finally {
            driver.close()
        }
    }

    /**
     * Construction reads nothing: the initial load runs on the given dispatcher, so a UI thread
     * resolving the instance never opens the database. Until it has run, [IdRemaps.loaded]
     * is false and the map is empty; [IdRemaps.awaitLoaded] returns once it has.
     */
    @Test
    fun theInitialLoadRunsOnTheDispatcherNotInTheConstructor() = runTest {
        var reads = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val remap = IdRemap(tagIds = mapOf("a" to "b"), folderIds = emptyMap())
        val remaps = IdRemaps({ reads++; remap }, dispatcher)

        assertEquals(0, reads, "construction does not read")
        assertEquals(false, remaps.loaded.value)
        assertTrue(remaps.current.value.isEmpty)

        remaps.awaitLoaded()

        assertEquals(1, reads)
        assertEquals(true, remaps.loaded.value)
        assertSame(remap, remaps.current.value)
    }

    /** A publish that lands before the initial load is not overtaken by it: the last read wins. */
    @Test
    fun aPublishBeforeTheInitialLoadIsNotOvertakenByIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reads = ArrayDeque(
            listOf(
                IdRemap(mapOf("a" to "b"), emptyMap()),
                IdRemap(mapOf("a" to "c"), emptyMap()),
            ),
        )
        val remaps = IdRemaps({ reads.removeFirst() }, dispatcher)

        // The publish takes the lock and reads first; the queued initial load then reads after it.
        remaps.publishFromDb()
        assertEquals("b", remaps.current.value.tag("a"))
        remaps.awaitLoaded()

        assertEquals("c", remaps.current.value.tag("a"), "the later read is the one current")
    }

    /** A failing initial load is logged, not thrown: the map stays empty, the gate still opens. */
    @Test
    fun aFailingInitialLoadStillOpensTheGate() = runTest {
        val remaps = IdRemaps({ error("db is locked") }, StandardTestDispatcher(testScheduler))

        remaps.awaitLoaded()

        assertEquals(true, remaps.loaded.value)
        assertTrue(remaps.current.value.isEmpty)
    }

    @Test
    fun theIdSetMappersKeepOrderAndKinds() {
        val remap = IdRemap(tagIds = mapOf("a" to "b"), folderIds = mapOf("a" to "z"))

        assertEquals(listOf("b", "x"), remap.tags(linkedSetOf("a", "x")).toList())
        assertEquals(listOf("x", "z"), remap.folders(linkedSetOf("x", "a")).toList())
    }

    @Test
    fun aRemapMovesOnlyTheIdsItMapsAndKeepsKinds() {
        val remap = IdRemap(tagIds = mapOf("a" to "b"), folderIds = mapOf("c" to "d"))

        assertEquals("b", remap.tag("a"))
        assertEquals("c", remap.tag("c"), "a folder entry never moves a tag id")
        assertEquals("d", remap.folder("c"))
        assertEquals("a", remap.folder("a"))
        assertEquals(ArticleFilter.Tag("b"), remap.filter(ArticleFilter.Tag("a")))
        assertEquals(ArticleFilter.Folder("d"), remap.filter(ArticleFilter.Folder("c")))
        val untouched = ArticleFilter.Tag("zzz")
        assertSame(untouched, remap.filter(untouched))
        assertEquals(ArticleFilter.Feed("a"), remap.filter(ArticleFilter.Feed("a")))
        assertEquals(ArticleFilter.All, remap.filter(ArticleFilter.All))
    }
}
