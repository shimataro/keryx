package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [DeltaExportSql]: the statements' shape. Their effect is covered by desktopTest's `DeltaExporterTest`. */
class DeltaExportSqlTest {

    private val delta = DeltaExportSql.deltaStatements(42)

    @Test
    fun everyKindCopiesOnlyTheTransportColumns() {
        for (statement in delta + DeltaExportSql.fullStatements(withExcept = true)) {
            assertFalse("search_text" in statement, statement)
            assertFalse("etag" in statement || "error_count" in statement, statement)
        }
        val articles = delta.single { it.startsWith("INSERT INTO main.articles") }
        val columns = TransportSchema.SYNCED_TABLES.first { it.name == "articles" }.columnNames
        assertTrue(articles.startsWith("INSERT INTO main.articles (${columns.joinToString(", ")})"), articles)
    }

    @Test
    fun aDeltaReadsOnlyOutboxEntriesUpToTheHighWaterMark() {
        val outboxReads = delta.filter { "sync_outbox" in it }
        assertEquals(9, outboxReads.size, "one per synced table, and one per identity edge table")
        for (statement in outboxReads) {
            assertTrue("seq <= 42" in statement, statement)
            assertFalse(Regex("seq\\s*(>|<(?!=))").containsMatchIn(statement), statement)
        }
    }

    @Test
    fun aDeltaWithholdsTheBodyOfAnArticleWithoutAHeavyEntry() {
        val articles = delta.single { it.startsWith("INSERT INTO main.articles") }
        assertTrue("CASE WHEN k.heavy = 1 THEN a.content ELSE NULL END AS content" in articles, articles)
        assertTrue("CASE WHEN k.heavy = 1 THEN a.summary ELSE NULL END AS summary" in articles, articles)
        assertTrue("MAX(heavy) AS heavy" in articles && "GROUP BY pk" in articles, articles)
        // Every other column, the body's stamp included, is copied as stored.
        assertTrue("a.body_updated_at" in articles, articles)
    }

    @Test
    fun bothKindsEndWithTheClosureFeedsAndTagsBeforeFolders() {
        val closure = DeltaExportSql.closureStatements
        assertEquals(closure, delta.dropLast(3).takeLast(closure.size))
        // then the identity edges recorded in the outbox
        assertTrue(delta.takeLast(3).all { "sync_outbox" in it && "_edges" in it })
        // A full send then writes the identity edges, which reference nothing.
        for (withExcept in listOf(false, true)) {
            assertEquals(
                closure + DeltaExportSql.edgeStatements(withExcept),
                DeltaExportSql.fullStatements(withExcept).takeLast(closure.size + 3),
            )
        }
        assertTrue(closure[0].startsWith("INSERT INTO main.feeds"))
        assertTrue(closure[1].startsWith("INSERT INTO main.tags"))
        assertTrue(closure[2].startsWith("INSERT INTO main.folders"))
        // Then, for a folder reference the file still holds no row for, its identity's edges before
        // the folder rows of that identity.
        assertTrue("INSERT OR IGNORE INTO main.folder_v2_edges" in closure[3], closure[3])
        assertTrue("INSERT INTO main.folders" in closure[4], closure[4])
        assertEquals(5, closure.size)
        closure.forEach { assertTrue("NOT IN (SELECT id FROM main." in it, it) }
    }

    @Test
    fun onlyAFullSendCarriesEveryIdentityEdge() {
        // A delta reads ks_v2_edge only in its closure (the folder edges its references need, and the
        // rows of those identities), never the whole table; besides that it carries the edges recorded
        // in the outbox up to its high-water mark.
        val edgeStatements = delta.filter { "ks_v2_edge" in it }
        assertEquals(DeltaExportSql.closureStatements.filter { "ks_v2_edge" in it }, edgeStatements)
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO main.folder_v2_edges (v2_id, id) SELECT pk, pk2 FROM src.sync_outbox WHERE tbl = 'folder_v2_edges' AND seq <= 42",
                "INSERT OR IGNORE INTO main.tag_v2_edges (v2_id, id) SELECT pk, pk2 FROM src.sync_outbox WHERE tbl = 'tag_v2_edges' AND seq <= 42",
                "INSERT OR IGNORE INTO main.feed_edges (a, b) SELECT pk, pk2 FROM src.sync_outbox WHERE tbl = 'feed_edges' AND seq <= 42",
            ),
            delta.takeLast(3),
        )
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO main.folder_v2_edges (v2_id, id) SELECT v2_id, id FROM src.ks_v2_edge WHERE kind = 'folder'",
                "INSERT OR IGNORE INTO main.tag_v2_edges (v2_id, id) SELECT v2_id, id FROM src.ks_v2_edge WHERE kind = 'tag'",
                "INSERT INTO main.feed_edges (a, b) SELECT a, b FROM src.ks_feed_edge",
            ),
            DeltaExportSql.edgeStatements(withExcept = false),
        )
        // With an except file, the edges it carries are left out, as its identical rows are.
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO main.folder_v2_edges (v2_id, id) SELECT v2_id, id FROM src.ks_v2_edge WHERE kind = 'folder' " +
                    "EXCEPT SELECT v2_id, id FROM ex.folder_v2_edges",
                "INSERT OR IGNORE INTO main.tag_v2_edges (v2_id, id) SELECT v2_id, id FROM src.ks_v2_edge WHERE kind = 'tag' " +
                    "EXCEPT SELECT v2_id, id FROM ex.tag_v2_edges",
                "INSERT INTO main.feed_edges (a, b) SELECT a, b FROM src.ks_feed_edge EXCEPT SELECT a, b FROM ex.feed_edges",
            ),
            DeltaExportSql.edgeStatements(withExcept = true),
        )
    }

    @Test
    fun aFullSendExceptsOnlyWhenAskedTo() {
        val plain = DeltaExportSql.fullStatements(withExcept = false)
        val except = DeltaExportSql.fullStatements(withExcept = true)
        assertTrue(plain.none { " EXCEPT " in it })
        assertEquals(9, except.count { " EXCEPT SELECT " in it && "FROM ex." in it })
        assertTrue(plain.none { "sync_outbox" in it })
    }

    @Test
    fun theClosureChecksReadTheTransportFileOnly() {
        assertEquals(SyncValidationSql.closureChecks.size, DeltaExportSql.closureChecks.size)
        for (check in DeltaExportSql.closureChecks) {
            assertFalse(Regex("FROM (feeds|folders|articles|feed_tags|tags)\\b").containsMatchIn(check.query), check.query)
            assertTrue("FROM main." in check.query, check.query)
        }
    }

    @Test
    fun pathsAreQuotedAndTablesChecked() {
        assertEquals("ATTACH DATABASE '/tmp/it''s.db' AS src", DeltaExportSql.attach("/tmp/it's.db", DeltaExportSql.SOURCE))
        assertEquals("VACUUM INTO '/tmp/it''s.db'", DeltaExportSql.vacuumInto("/tmp/it's.db"))
        assertEquals("VACUUM INTO '/tmp/copy.db'", DeltaExportSql.vacuumInto("/tmp/copy.db"))
        assertFailsWith<IllegalArgumentException> { DeltaExportSql.countQuery("sync_outbox") }
        assertFailsWith<IllegalArgumentException> { DeltaExportSql.deltaStatements(-1) }
    }
}
