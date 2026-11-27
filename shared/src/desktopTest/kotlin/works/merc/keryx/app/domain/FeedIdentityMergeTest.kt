package works.merc.keryx.app.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import works.merc.keryx.app.data.local.articleVisibleSql
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.TransportFixtures.DEV_A
import works.merc.keryx.app.domain.TransportFixtures.article
import works.merc.keryx.app.domain.TransportFixtures.baseName
import works.merc.keryx.app.domain.TransportFixtures.deltaName
import works.merc.keryx.app.domain.TransportFixtures.exec
import works.merc.keryx.app.domain.TransportFixtures.feed
import works.merc.keryx.app.domain.TransportFixtures.feedTag
import works.merc.keryx.app.domain.TransportFixtures.fold
import works.merc.keryx.app.domain.TransportFixtures.folder
import works.merc.keryx.app.domain.TransportFixtures.identityState
import works.merc.keryx.app.domain.TransportFixtures.localDb
import works.merc.keryx.app.domain.TransportFixtures.merge
import works.merc.keryx.app.domain.TransportFixtures.rows
import works.merc.keryx.app.domain.TransportFixtures.searchPendingIds
import works.merc.keryx.app.domain.TransportFixtures.tag
import works.merc.keryx.app.domain.TransportFixtures.transportFile
import works.merc.keryx.app.fileDb
import works.merc.keryx.app.platform.DatabaseMerger
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The feed and article identity step of every merge ([FeedIdentitySql]): feeds that held one URL
 * under different ids, on main and in the cloud file in either direction, end on one canonical feed
 * (the smallest id) with folded fields, and their articles on one row per `(feed, guid)`. Everything
 * is forced by the shape of the files, never by timing.
 */
class FeedIdentityMergeTest {

    private val url = "https://shared/feed"

    /** Tags and folders both sides know, so every reference is satisfied. */
    private fun common() = arrayOf(tag("t1", "news"), tag("t2", "tech"), folder("d1", "Work"), folder("d2", "Home"))

    /** The "P" version of the feed (the smaller id), stamped newer on content, folder and body. */
    private fun pRows() = arrayOf(
        feed(
            "fp", url = url, title = "P title", updatedAt = 150, createdAt = 70, folderId = "d2", folderAt = 250,
            sortOrder = 2, sortAt = 200, customTitle = "P custom", customAt = 100,
        ),
        feedTag("fp", "t1", updatedAt = 200),
        feedTag("fp", "t2", updatedAt = 100),
        article(
            "ap1", "fp", guid = "g1", title = "P article", content = "p body", updatedAt = 300, bodyAt = 300,
            isRead = 0, readAt = 100, isStarred = 1, starredAt = 400, cachedAt = 90, createdAt = 60,
        ),
        article("ap3", "fp", guid = "g3"),
    )

    /** The "Q" version of the same feed (the greater id), newer on sort order, custom title and read. */
    private fun qRows() = arrayOf(
        feed(
            "fq", url = url, title = "Q title", updatedAt = 100, createdAt = 90, folderId = "d1", folderAt = 200,
            sortOrder = 5, sortAt = 300, customTitle = "Q custom", customAt = 300,
        ),
        feedTag("fq", "t1", updatedAt = 100),
        article(
            "aq1", "fq", guid = "g1", title = "Q article", content = "q body", updatedAt = 100, bodyAt = 100,
            isRead = 1, readAt = 200, cachedAt = 150, createdAt = 80,
        ),
        article("aq2", "fq", guid = "g2"),
    )

    private fun deltaOf(vararg rows: String) = transportFile(deltaName(DEV_A, 1), *common(), *rows)

    private fun foreignKeyViolations(file: File) = rows(file, "PRAGMA foreign_key_check")

    /** Merges as `DeltaSync.mergeFolded` does: inside the `sync_merging` marker, foreign keys on. */
    private fun mergeWithMarker(local: File, cloud: File) {
        val statements = listOf("INSERT INTO main.sync_merging (flag) VALUES (1)") + MergeSql.transport + "DELETE FROM main.sync_merging"
        DatabaseMerger.mergeTransport(local.absolutePath, cloud.absolutePath, statements)
    }

    /** A full database (the pre-delta cloud file) holding [rows]. */
    private fun fullDb(vararg rows: String): File {
        val (file, driver, _) = fileDb()
        driver.close()
        exec(file, *rows)
        return file
    }

    private fun mergePreDelta(local: File, cloud: File) =
        DatabaseMerger.merge(local.absolutePath, cloud.absolutePath, 1L, MergeSql.all)

    /** The folded result of [qRows] and [pRows], whichever side held which. */
    private fun assertFolded(db: File) {
        assertEquals(
            listOf("fp|$url|P title|70|5|Q custom|null|d2"),
            rows(db, "SELECT id, url, title, created_at, sort_order, custom_title, deleted_at, folder_id FROM feeds"),
        )
        assertEquals(listOf("fp|250|d2"), rows(db, "SELECT feed_id, folder_updated_at, folder_id FROM ks_feed_folder_raw"))
        assertEquals(listOf("fp|t1|200|null", "fp|t2|100|null"), rows(db, "SELECT feed_id, tag_id, updated_at, deleted_at FROM feed_tags ORDER BY 1, 2"))
        // g1 collided: the body from P (newer body stamp), read from Q (newer read), star from P, cached
        // and updated the latest, created the earliest.
        assertEquals(
            listOf("ap1|fp|g1|P article|p body|1|200|1|400|150|60|300|300"),
            rows(
                db,
                "SELECT id, feed_id, guid, title, content, is_read, read_at, is_starred, starred_at, cached_at, created_at, " +
                    "updated_at, body_updated_at FROM articles WHERE guid = 'g1'",
            ),
        )
        assertEquals(listOf("ap1|fp", "ap3|fp", "aq2|fp"), rows(db, "SELECT id, feed_id FROM articles ORDER BY id"))
        assertEquals(listOf("article|aq1|ap1", "feed|fq|fp"), rows(db, "SELECT kind, old_id, new_id FROM ks_id_remap WHERE kind IN ('feed', 'article') ORDER BY 1, 2"))
        assertEquals(listOf("fp|fq"), rows(db, "SELECT a, b FROM ks_feed_edge"))
        assertEquals(emptyList(), foreignKeyViolations(db))
    }

    // ---- review finding #1: a star on one row and a cleanup of the other, merged into one ----

    /** P's g1 starred at 100; Q's g1 deleted by a cleanup at 200 (never starred there). */
    private fun starredP() = arrayOf(
        feed("fp", url = url, title = "P title", updatedAt = 100),
        article("ap1", "fp", guid = "g1", isStarred = 1, starredAt = 100),
    )

    private fun deletedQ() = arrayOf(
        feed("fq", url = url, title = "Q title", updatedAt = 100),
        article("aq1", "fq", guid = "g1", deletedAt = 200, deletedUpdatedAt = 200),
    )

    /** The merged g1 keeps both groups' winners — the star and the deletion — and is visible. */
    private fun assertStarKeptAndVisible(db: File, label: String) {
        assertEquals(
            listOf("ap1|1|100|200|200"),
            rows(db, "SELECT id, is_starred, starred_at, deleted_at, deleted_updated_at FROM articles WHERE guid = 'g1'"),
            label,
        )
        assertEquals(
            listOf("ap1"),
            rows(db, "SELECT id FROM articles WHERE guid = 'g1' AND ${articleVisibleSql()}"),
            label,
        )
    }

    @Test
    fun aStarOnOneRowSurvivesACleanupOfTheOtherRowInASyncMergeInBothDirections() {
        assertStarKeptAndVisible(localDb(*common(), *starredP()).also { merge(it, deltaOf(*deletedQ())) }, "main starred")
        assertStarKeptAndVisible(localDb(*common(), *deletedQ()).also { merge(it, deltaOf(*starredP())) }, "main deleted")
        // A fold of both, merged anywhere, agrees.
        val folded = fold(deltaOf(*starredP()) to deltaName(DEV_A, 1), deltaOf(*deletedQ()) to deltaName(DEV_A, 2))
        assertStarKeptAndVisible(localDb(*common()).also { merge(it, folded) }, "merged fold")
    }

    @Test
    fun aStarOnOneRowSurvivesACleanupOfTheOtherRowInALocalMerge() {
        // As a local 301 does (FeedRepository): both feeds on this device, merged with FeedIdentitySql.mergeLocally.
        val local = localDb(*common(), *starredP(), *deletedQ().map { it.replace(url, "https://other/feed") }.toTypedArray())
        val driver = JdbcSqliteDriver("jdbc:sqlite:${local.absolutePath}")
        try {
            // In a transaction, as FeedRepository runs it: the JDBC driver keeps one connection only
            // inside one, and the statements share temp tables.
            KeryxDatabase(driver).transaction { FeedIdentitySql.mergeLocally(driver, "fq", "fp") }
        } finally {
            driver.close()
        }
        assertStarKeptAndVisible(local, "local merge")
    }

    // ---- a collision between main and the cloud file, both directions ----

    @Test
    fun mainHoldingTheGreaterIdAndTheFileTheSmallerFoldOntoTheSmallerIdWithNothingLost() {
        val local = localDb(*common(), *qRows(), "INSERT INTO feed_fetch_state (feed_id, etag, error_count) VALUES ('fq', 'q-etag', 2)")
        merge(local, deltaOf(*pRows()))
        assertFolded(local)
        // The canonical feed existed only in the cloud file: main's device-local fetch state moves over, but
        // without its validators — the moving feed's url was set aside by the re-key, and a validator
        // belongs to the url it was fetched from — while its error count stays.
        assertEquals(listOf("fp|null|2"), rows(local, "SELECT feed_id, etag, error_count FROM feed_fetch_state"))
        assertTrue("ap1" in searchPendingIds(local), "the survivor's merged body must be re-indexed")
    }

    @Test
    fun mainHoldingTheSmallerIdAndTheFileTheGreaterFoldOntoTheSameState() {
        val local = localDb(*common(), *pRows(), "INSERT INTO feed_fetch_state (feed_id, etag, error_count) VALUES ('fp', 'p-etag', 0)")
        merge(local, deltaOf(*qRows()))
        assertFolded(local)
        // The canonical feed is main's own: its fetch state stays.
        assertEquals(listOf("fp|p-etag|0"), rows(local, "SELECT feed_id, etag, error_count FROM feed_fetch_state"))
        // Both directions leave the very same synced state, remap and edges.
        val other = localDb(*common(), *qRows()).also { merge(it, deltaOf(*pRows())) }
        assertEquals(identityState(other), identityState(local))
    }

    @Test
    fun theCanonicalFeedKeepsItsOwnFetchStateAndTheOtherSidesIsDropped() {
        // Both rows held by main (the URL moved before the edge arrived): its two fetch states.
        val local = localDb(*common(), *pRows(), feed("fq", url = "https://elsewhere/feed", updatedAt = 100))
        exec(local, "INSERT INTO feed_fetch_state (feed_id, etag) VALUES ('fp', 'p-etag')")
        exec(local, "INSERT INTO feed_fetch_state (feed_id, etag) VALUES ('fq', 'q-etag')")
        merge(local, transportFile(deltaName(DEV_A, 1), "INSERT INTO feed_edges (a, b) VALUES ('fp', 'fq')"))
        assertEquals(listOf("fp|p-etag"), rows(local, "SELECT feed_id, etag FROM feed_fetch_state"))
        assertEquals(listOf("fp"), rows(local, "SELECT id FROM feeds"))
        assertEquals(emptyList(), foreignKeyViolations(local))
    }

    /**
     * The canonical feed's validators describe the url it was fetched from. The other feed's newer content
     * gives the merged feed that feed's url, so the validators are dropped (the next fetch is
     * unconditional) — and kept where the merged feed still holds its own url (the test above).
     */
    @Test
    fun theCanonicalFeedTakingTheOtherFeedsUrlLosesItsValidators() {
        val local = localDb(
            *common(),
            feed("fp", url = "https://old/feed", updatedAt = 100),
            feed("fq", url = "https://new/feed", updatedAt = 200),
            "INSERT INTO feed_fetch_state (feed_id, etag, last_modified, error_count) VALUES ('fp', 'p-etag', 'p-lm', 3)",
        )
        merge(local, transportFile(deltaName(DEV_A, 1), "INSERT INTO feed_edges (a, b) VALUES ('fp', 'fq')"))
        assertEquals(listOf("fp|https://new/feed"), rows(local, "SELECT id, url FROM feeds"))
        assertEquals(listOf("fp|null|null|3"), rows(local, "SELECT feed_id, etag, last_modified, error_count FROM feed_fetch_state"))
    }

    @Test
    fun aTombstonedFolderResolvesToNoFolderWhileTheRawAssignmentIsKept() {
        val d2Deleted = folder("d2", "Home", updatedAt = 300, deletedAt = 300)
        val local = localDb(tag("t1", "news"), tag("t2", "tech"), folder("d1", "Work"), d2Deleted, *qRows())
        merge(local, transportFile(deltaName(DEV_A, 1), tag("t1", "news"), tag("t2", "tech"), folder("d1", "Work"), d2Deleted, *pRows()))
        assertEquals(listOf("fp|null"), rows(local, "SELECT id, folder_id FROM feeds"))
        assertEquals(listOf("fp|250|d2"), rows(local, "SELECT feed_id, folder_updated_at, folder_id FROM ks_feed_folder_raw"))
        assertEquals(emptyList(), foreignKeyViolations(local))
    }

    // ---- both feeds already in main; the survivor and rowids ----

    @Test
    fun ofArticlesSharingAKeyTheLargestRowidSurvivesAndAVanishedRowidIsNeverHandedOutAgain() {
        // Both feeds are main's own (different URLs: the collision was not known yet); each holds g1.
        val local = localDb(
            *common(),
            feed("fp", url = "https://one/feed", updatedAt = 100),
            feed("fq", url = "https://two/feed", updatedAt = 200),
            article("ap1", "fp", guid = "g1", content = "p body", updatedAt = 300, bodyAt = 300, isRead = 1, readAt = 300),
            article("aq1", "fq", guid = "g1", content = "q body", updatedAt = 100, bodyAt = 100, isRead = 0, readAt = 100),
            "INSERT INTO ks_search_pending (rowid) VALUES (1)",
        )
        assertEquals(listOf("1|ap1", "2|aq1"), rows(local, "SELECT rowid, id FROM articles ORDER BY rowid"))

        merge(local, transportFile(deltaName(DEV_A, 1), "INSERT INTO feed_edges (a, b) VALUES ('fp', 'fq')"))

        // The row with rowid 2 survives (it takes the canonical id in place, with the folded values);
        // rowid 1 is gone, and the new body is registered for indexing under the survivor's rowid.
        assertEquals(listOf("2|ap1|fp|p body|1"), rows(local, "SELECT rowid, id, feed_id, content, is_read FROM articles"))
        assertEquals(listOf("2"), rows(local, "SELECT rowid FROM ks_search_pending"))
        // The canonical feed took the URL of the newer content version, which the other row had held.
        assertEquals(listOf("fp|https://two/feed"), rows(local, "SELECT id, url FROM feeds"))
        // A new article does not reuse the vanished rowid.
        exec(local, article("ax", "fp", guid = "g9"))
        assertEquals(listOf("3"), rows(local, "SELECT rowid FROM articles WHERE id = 'ax'"))
        assertEquals(emptyList(), foreignKeyViolations(local))
    }

    // ---- the outbox ----

    @Test
    fun theNotYetSentOutboxEntriesNameTheCanonicalIdsAndTheMergeAddsOnlyTheEdge() {
        val local = localDb("UPDATE sync_capture SET enabled = 1", *common(), *qRows())
        val outboxSql = "SELECT tbl, pk, COALESCE(pk2, '') FROM sync_outbox WHERE tbl IN ('feeds', 'feed_tags', 'articles', 'feed_edges') ORDER BY 1, 2, 3"
        assertEquals(
            listOf("articles|aq1|", "articles|aq2|", "feed_tags|fq|t1", "feeds|fq|"),
            rows(local, outboxSql),
        )
        val before = rows(local, "SELECT COUNT(*) FROM sync_outbox").single().toLong()

        mergeWithMarker(local, deltaOf(*pRows()))

        assertEquals(
            listOf("articles|ap1|", "articles|aq2|", "feed_edges|fp|fq", "feed_tags|fp|t1", "feeds|fp|"),
            rows(local, outboxSql),
        )
        // The merge recorded none of its own writes: the one new row is the edge.
        assertEquals(before + 1, rows(local, "SELECT COUNT(*) FROM sync_outbox").single().toLong())
        assertFolded(local)
    }

    // ---- search bookkeeping ----

    @Test
    fun anArticleAwaitingItsBodyKeepsWaitingUnderItsCanonicalId() {
        val local = localDb(*common(), feed("fq", url = url), article("aq1", "fq", guid = "g1"), "INSERT INTO ks_search_awaiting_body (id) VALUES ('aq1')")
        merge(local, deltaOf(feed("fp", url = url), article("ap1", "fp", guid = "g1")))
        assertEquals(listOf("ap1|fp"), rows(local, "SELECT id, feed_id FROM articles"))
        assertEquals(listOf("ap1"), rows(local, "SELECT id FROM ks_search_awaiting_body"))
    }

    // ---- foreign keys ----

    @Test
    fun anOrphanChildOfAMovingIdFollowsItToTheCanonicalFeedAndTheMergeNeverRaisesAViolation() {
        // fq has no row at all: its article (colliding with fp's own) and its link are orphans main held.
        val local = localDb(
            *common(), feed("fp", url = url), article("ap1", "fp", guid = "g1"),
            article("aq1", "fq", guid = "g1", content = "orphan body", bodyAt = 500, updatedAt = 500), feedTag("fq", "t1"),
        )
        assertTrue(foreignKeyViolations(local).isNotEmpty())
        merge(local, transportFile(deltaName(DEV_A, 1), "INSERT INTO feed_edges (a, b) VALUES ('fp', 'fq')"))
        assertEquals(listOf("ap1|fp|orphan body"), rows(local, "SELECT id, feed_id, content FROM articles"))
        assertEquals(listOf("fp|t1"), rows(local, "SELECT feed_id, tag_id FROM feed_tags"))
        assertEquals(emptyList(), foreignKeyViolations(local))
    }

    @Test
    fun anOrphanChildOfAnIdWhoseCanonicalFeedExistsNowhereIsLeftAsItWasAndNothingIsRaised() {
        val local = localDb(*common(), article("aq1", "fq", guid = "g1"), feedTag("fq", "t1"))
        val before = foreignKeyViolations(local)
        assertTrue(before.isNotEmpty())
        merge(local, transportFile(deltaName(DEV_A, 1), "INSERT INTO feed_edges (a, b) VALUES ('fp', 'fq')"))
        // Still pointing at the id no row carries: no new violation, and nothing else moved.
        assertEquals(before, foreignKeyViolations(local))
        assertEquals(listOf("aq1|fq"), rows(local, "SELECT id, feed_id FROM articles"))
    }

    @Test
    fun nothingIsRaisedWithForeignKeysOnWhenTheCanonicalFeedIsOnlyInTheFile() {
        val local = localDb(*common(), *qRows())
        merge(local, deltaOf(*pRows()))
        assertEquals(emptyList(), foreignKeyViolations(local))
        val onlyMain = localDb(*common(), *pRows())
        merge(onlyMain, deltaOf(*qRows()))
        assertEquals(emptyList(), foreignKeyViolations(onlyMain))
    }

    // ---- idempotence and every merge path ----

    private fun fullState(file: File): Map<String, List<String>> = identityState(file) + mapOf(
        "fetch" to rows(file, "SELECT feed_id, etag, last_modified, error_count, last_error FROM feed_fetch_state ORDER BY 1"),
        "rowids" to rows(file, "SELECT rowid, id FROM articles ORDER BY rowid"),
        "pending" to rows(file, "SELECT rowid FROM ks_search_pending ORDER BY 1"),
        "awaiting" to rows(file, "SELECT id FROM ks_search_awaiting_body ORDER BY 1"),
        "outbox" to rows(file, "SELECT tbl, pk, COALESCE(pk2, '') FROM sync_outbox ORDER BY seq"),
    )

    @Test
    fun mergingTheSameFileASecondTimeChangesNothing() {
        for (mainIsQ in listOf(true, false)) {
            val local = localDb(
                "UPDATE sync_capture SET enabled = 1", *common(), *(if (mainIsQ) qRows() else pRows()),
                "INSERT INTO feed_fetch_state (feed_id, etag) VALUES ('${if (mainIsQ) "fq" else "fp"}', 'e')",
            )
            val file = deltaOf(*(if (mainIsQ) pRows() else qRows()))
            mergeWithMarker(local, file)
            val once = fullState(local)
            mergeWithMarker(local, file)
            assertEquals(once, fullState(local), "mainIsQ=$mainIsQ")
        }
    }

    @Test
    fun aDeltaABaseTheFoldOfBothAndThePreDeltaPathAllConsolidateOnTheSameState() {
        val expected = localDb(*common(), *qRows()).also { merge(it, deltaOf(*pRows())) }.let(::identityState)

        val asBase = transportFile(baseName(DEV_A, 1), *common(), *pRows())
        assertEquals(expected, identityState(localDb(*common(), *qRows()).also { merge(it, asBase) }))

        val name = deltaName(DEV_A, 1)
        val folded = fold(deltaOf(*pRows()) to name)
        assertEquals(expected, identityState(localDb(*common(), *qRows()).also { merge(it, folded) }))

        // A fold of the two sides' files, merged into an empty device, equals both merged one by one.
        val both = fold(deltaOf(*pRows()) to name, transportFile(deltaName(TransportFixtures.DEV_B, 1), *common(), *qRows()) to deltaName(TransportFixtures.DEV_B, 1))
        assertEquals(expected, identityState(localDb().also { merge(it, both) }))

        // The pre-delta path: a full database as the cloud file, the collision found from the URLs.
        val preDelta = localDb(*common(), *qRows()).also { mergePreDelta(it, fullDb(*common(), *pRows())) }
        assertEquals(expected, identityState(preDelta))
        assertFolded(preDelta)
    }

    @Test
    fun mergingTheSamePreDeltaFileASecondTimeChangesNothing() {
        for (mainIsQ in listOf(true, false)) {
            val local = localDb(
                "UPDATE sync_capture SET enabled = 1", *common(), *(if (mainIsQ) qRows() else pRows()),
                "INSERT INTO feed_fetch_state (feed_id, etag) VALUES ('${if (mainIsQ) "fq" else "fp"}', 'e')",
            )
            val cloud = fullDb(*common(), *(if (mainIsQ) pRows() else qRows()))
            mergePreDelta(local, cloud)
            val once = fullState(local)
            assertFolded(local)
            mergePreDelta(local, cloud)
            assertEquals(once, fullState(local), "mainIsQ=$mainIsQ")
            assertFolded(local)
        }
    }

    // ---- a chain of three ids linked by separate files ----

    /** fa-fb are linked by the first file's edge, fb-fc by the second's; the third carries only fc. */
    private fun chainFiles(): List<Pair<File, TransportFileName>> {
        val first = deltaName(DEV_A, 1)
        val second = deltaName(DEV_A, 2)
        val third = deltaName(TransportFixtures.DEV_B, 1)
        return listOf(
            transportFile(
                first, *common(),
                feed("fa", url = "https://chain/a", updatedAt = 100), feed("fb", url = "https://chain/b", updatedAt = 100),
                feedTag("fa", "t1"), article("aa1", "fa", guid = "g1"),
                article("ab1", "fb", guid = "g1"), article("ab2", "fb", guid = "g2"),
                "INSERT INTO feed_edges (a, b) VALUES ('fa', 'fb')",
            ) to first,
            transportFile(
                second, *common(),
                feed("fb", url = "https://chain/b", updatedAt = 100), feedTag("fb", "t2"), article("ab2", "fb", guid = "g2"),
                "INSERT INTO feed_edges (a, b) VALUES ('fb', 'fc')",
            ) to second,
            transportFile(
                third, *common(),
                feed("fc", url = "https://chain/c", updatedAt = 100), feedTag("fc", "t1"),
                article("ac1", "fc", guid = "g1"), article("ac3", "fc", guid = "g3"),
            ) to third,
        )
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items)
        else items.flatMap { head -> permutations(items - head).map { listOf(head) + it } }

    private fun assertChainConsolidated(db: File, label: String) {
        assertEquals(listOf("fa"), rows(db, "SELECT id FROM feeds"), label)
        assertEquals(listOf("fa|g1", "fa|g2", "fa|g3"), rows(db, "SELECT feed_id, guid FROM articles ORDER BY guid"), label)
        assertEquals(listOf("fa|t1", "fa|t2"), rows(db, "SELECT feed_id, tag_id FROM feed_tags ORDER BY 2"), label)
        // Both other ids map straight to the smallest one: one hop, never through fb.
        assertEquals(listOf("fb|fa", "fc|fa"), rows(db, "SELECT old_id, new_id FROM ks_id_remap WHERE kind = 'feed' ORDER BY 1"), label)
        assertEquals(emptyList(), foreignKeyViolations(db), label)
    }

    @Test
    fun aChainOfThreeIdsLinkedBySeparateFilesEndsOnTheSmallestIdInEveryArrivalOrder() {
        val files = chainFiles()
        val expected = identityState(localDb().also { db -> files.forEach { merge(db, it.first) } })
        assertChainConsolidated(localDb().also { db -> files.forEach { merge(db, it.first) } }, "in order")

        for (order in permutations(files)) {
            val label = order.joinToString(" ") { it.second.number.toString() + it.second.device.take(1) }
            val db = localDb()
            order.forEach { merge(db, it.first) }
            assertChainConsolidated(db, label)
            assertEquals(expected, identityState(db), label)
        }
    }

    @Test
    fun aChainOfThreeIdsFoldedFirstEndsOnTheSameStateInEveryOrder() {
        val files = chainFiles()
        val expected = identityState(localDb().also { db -> files.forEach { merge(db, it.first) } })
        for (order in permutations(files)) {
            val label = order.joinToString(" ") { it.second.number.toString() + it.second.device.take(1) }
            val db = localDb()
            merge(db, fold(*order.toTypedArray()))
            assertChainConsolidated(db, "folded $label")
            assertEquals(expected, identityState(db), "folded $label")
        }
    }

    // ---- an article id both sides hold under different keys ----

    @Test
    fun anArticleIdHeldUnderDifferentKeysRaisesNothingAndConvergesOnceTheEdgeArrives() {
        fun main() = localDb(
            *common(),
            feed("fp", url = "https://one/feed", updatedAt = 100), feed("fq", url = "https://two/feed", updatedAt = 100),
            article("b1", "fp", guid = "g1", content = "b body", updatedAt = 100, bodyAt = 100),
            article("a1", "fq", guid = "g1", content = "a body", updatedAt = 100, bodyAt = 100),
        )
        // The file knows a1 under fp (and both feeds), but nothing says fp and fq are one feed yet.
        val file = transportFile(
            deltaName(DEV_A, 1), *common(),
            feed("fp", url = "https://one/feed", updatedAt = 100), feed("fq", url = "https://two/feed", updatedAt = 100),
            article("a1", "fp", guid = "g1", content = "a body", updatedAt = 100, bodyAt = 100, isRead = 1, readAt = 300),
        )
        val edge = transportFile(deltaName(DEV_A, 2), "INSERT INTO feed_edges (a, b) VALUES ('fp', 'fq')")

        val viaFileFirst = main().also { merge(it, file) }
        // Transitional: every id keeps exactly one key (main's), and nothing violates a constraint. The
        // file's row for a1 is not applied while main holds its (feed, guid) under b1 (`MergeSql`'s
        // defensive guard), so the read state it carries has not arrived yet.
        assertEquals(listOf("a1|fq|g1|0", "b1|fp|g1|0"), rows(viaFileFirst, "SELECT id, feed_id, guid, is_read FROM articles ORDER BY id"))
        assertEquals(emptyList(), foreignKeyViolations(viaFileFirst))
        merge(viaFileFirst, edge)
        // The edge consolidates the two rows onto one; a later delivery of the file's content
        // (a base, say) then brings the state it carries.
        assertEquals(listOf("a1|fp|g1"), rows(viaFileFirst, "SELECT id, feed_id, guid FROM articles"))
        merge(viaFileFirst, file)

        val viaEdgeFirst = main().also { merge(it, edge); merge(it, file) }

        for (db in listOf(viaFileFirst, viaEdgeFirst)) {
            assertEquals(listOf("a1|fp|g1|1"), rows(db, "SELECT id, feed_id, guid, is_read FROM articles"))
            assertEquals(listOf("fp"), rows(db, "SELECT id FROM feeds"))
            assertEquals(listOf("article|b1|a1", "feed|fq|fp"), rows(db, "SELECT kind, old_id, new_id FROM ks_id_remap WHERE kind IN ('feed', 'article') ORDER BY 1, 2"))
            assertEquals(emptyList(), foreignKeyViolations(db))
        }
        assertEquals(identityState(viaFileFirst), identityState(viaEdgeFirst))
    }
}
