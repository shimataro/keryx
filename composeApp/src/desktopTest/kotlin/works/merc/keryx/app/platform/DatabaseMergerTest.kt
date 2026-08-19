package works.merc.keryx.app.platform

import works.merc.keryx.app.fileDb
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies [DatabaseMerger.validateSchema] tells apart "inspection completed and found the schema
 * invalid" ([Boolean] `false`) from "inspection itself failed" ([Boolean]`?` `null`) — conflating
 * the two would let a transient open/IO failure trigger the same destructive cloud-data-reset path
 * as a genuinely incompatible schema (see [works.merc.keryx.app.domain.MergeFailureClassifier]).
 */
class DatabaseMergerTest {

    @Test
    fun validSchemaReturnsTrue() {
        val (file, driver, _) = fileDb()
        try {
            assertTrue(DatabaseMerger.validateSchema(file.absolutePath, SCHEMA_VERSION) == true)
        } finally {
            driver.close()
            file.delete()
        }
    }

    @Test
    fun missingTableReturnsFalseWhenInspectionCompletes() {
        // An empty file is a valid (empty) SQLite database: opening it succeeds, but none of the
        // expected tables exist, so PRAGMA table_info returns no rows for any of them.
        val file = File.createTempFile("keryx-merger-test-", ".db").apply { deleteOnExit() }
        try {
            assertFalse(DatabaseMerger.validateSchema(file.absolutePath, SCHEMA_VERSION) ?: error("expected false"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun failedInspectionReturnsNullRatherThanFalse() {
        // A directory cannot be opened as a SQLite database, so the connection attempt itself
        // throws — this must be undetermined (null), not "confirmed invalid" (false).
        val directory = File.createTempFile("keryx-merger-test-dir-", "").apply {
            delete()
            mkdir()
            deleteOnExit()
        }
        try {
            assertNull(DatabaseMerger.validateSchema(directory.absolutePath, SCHEMA_VERSION))
        } finally {
            directory.delete()
        }
    }

    @Test
    fun unregisteredSchemaVersionReturnsNull() {
        assertEquals(null, DatabaseMerger.validateSchema("irrelevant", schemaVersion = Long.MAX_VALUE))
    }

    private companion object {
        /** Must stay a version actually registered in [works.merc.keryx.app.domain.MergeSchema.EXPECTED_SCHEMAS]. */
        const val SCHEMA_VERSION = 2L
    }
}
