package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotSqlTest {

    @Test
    fun vacuumIntoStatementEscapesSingleQuotes() {
        assertEquals(
            "VACUUM INTO '/tmp/it''s.db'",
            SnapshotSql.vacuumIntoStatement("/tmp/it's.db"),
        )
    }

    @Test
    fun vacuumIntoStatementWithNoQuotesNeedsNoEscaping() {
        assertEquals("VACUUM INTO '/tmp/snapshot.db'", SnapshotSql.vacuumIntoStatement("/tmp/snapshot.db"))
    }

    @Test
    fun cleanupStatementsWithNoExtrasMatchesDesktopsOwnOrder() {
        assertEquals(
            listOf(
                "DROP TABLE IF EXISTS articles_fts",
                "DROP TABLE IF EXISTS sync_state",
                "DROP INDEX IF EXISTS idx_articles_feed_id",
                "DROP INDEX IF EXISTS idx_articles_is_read",
                "DROP INDEX IF EXISTS idx_articles_is_starred",
                "DROP INDEX IF EXISTS idx_articles_published",
                "VACUUM",
            ),
            SnapshotSql.cleanupStatements(),
        )
    }

    @Test
    fun extraDropTablesAreInsertedAfterSharedTablesAndBeforeIndexes() {
        val statements = SnapshotSql.cleanupStatements(extraDropTables = listOf("android_metadata"))
        assertEquals(
            listOf(
                "DROP TABLE IF EXISTS articles_fts",
                "DROP TABLE IF EXISTS sync_state",
                "DROP TABLE IF EXISTS android_metadata",
                "DROP INDEX IF EXISTS idx_articles_feed_id",
                "DROP INDEX IF EXISTS idx_articles_is_read",
                "DROP INDEX IF EXISTS idx_articles_is_starred",
                "DROP INDEX IF EXISTS idx_articles_published",
                "VACUUM",
            ),
            statements,
        )
    }

    @Test
    fun vacuumIsAlwaysTheLastStatementRegardlessOfExtraDropTables() {
        assertEquals("VACUUM", SnapshotSql.cleanupStatements().last())
        assertEquals("VACUUM", SnapshotSql.cleanupStatements(extraDropTables = listOf("a", "b")).last())
    }
}
