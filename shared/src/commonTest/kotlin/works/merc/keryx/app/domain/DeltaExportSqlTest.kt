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
        val columns = TransportSchema.SYNCED_TABLES_V1.first { it.name == "articles" }.columnNames
        assertTrue(articles.startsWith("INSERT INTO main.articles (${columns.joinToString(", ")})"), articles)
    }

    @Test
    fun aDeltaReadsOnlyOutboxEntriesUpToTheHighWaterMark() {
        val outboxReads = delta.filter { "sync_outbox" in it }
        assertEquals(6, outboxReads.size, "one per synced table")
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
        for (statements in listOf(delta, DeltaExportSql.fullStatements(false), DeltaExportSql.fullStatements(true))) {
            assertEquals(DeltaExportSql.closureStatements, statements.takeLast(3))
        }
        val closure = DeltaExportSql.closureStatements
        assertTrue(closure[0].startsWith("INSERT INTO main.feeds"))
        assertTrue(closure[1].startsWith("INSERT INTO main.tags"))
        assertTrue(closure[2].startsWith("INSERT INTO main.folders"))
        closure.forEach { assertTrue("NOT IN (SELECT id FROM main." in it, it) }
    }

    @Test
    fun aFullSendExceptsOnlyWhenAskedTo() {
        val plain = DeltaExportSql.fullStatements(withExcept = false)
        val except = DeltaExportSql.fullStatements(withExcept = true)
        assertTrue(plain.none { " EXCEPT " in it })
        assertEquals(6, except.count { " EXCEPT SELECT " in it && "FROM ex." in it })
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
        assertFailsWith<IllegalArgumentException> { DeltaExportSql.countQuery("sync_outbox") }
        assertFailsWith<IllegalArgumentException> { DeltaExportSql.deltaStatements(-1) }
    }
}
