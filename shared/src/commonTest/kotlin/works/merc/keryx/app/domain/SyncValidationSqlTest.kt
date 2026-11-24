package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure parts of [SyncValidationSql]; the queries themselves run against real files in desktopTest. */
class SyncValidationSqlTest {

    private val gen = "0f1e2d3c-4b5a-4968-8778-695a4b3c2d1e"
    private val dev = "a1b2c3d4-e5f6-4789-8abc-def012345678"
    private val delta = TransportFileName(gen, TransportFileKind.DELTA, dev, 7, 1_000, "0123456789ab")
    private val base = TransportFileName(gen, TransportFileKind.BASE, dev, 2, 1_000, "0123456789ab")

    private fun meta(name: TransportFileName) = TransportMeta(name.generation, name.kind, name.device, name.number, name.createdAt).rows

    @Test
    fun violationConditionsFollowTheColumnKind() {
        assertEquals("(typeof(\"id\") <> 'text' OR \"id\" = '')", SyncValidationSql.violation(req("id", ColumnKind.ID)))
        assertEquals(
            "(\"folder_id\" IS NOT NULL AND (typeof(\"folder_id\") <> 'text' OR \"folder_id\" = ''))",
            SyncValidationSql.violation(opt("folder_id", ColumnKind.ID)),
        )
        assertEquals("(typeof(\"x\") <> 'integer' OR \"x\" NOT IN (0, 1))", SyncValidationSql.violation(req("x", ColumnKind.BOOLEAN)))
        assertEquals("(\"s\" IS NOT NULL AND (typeof(\"s\") <> 'integer'))", SyncValidationSql.violation(opt("s", ColumnKind.STAMP)))
        assertEquals("(typeof(\"n\") <> 'integer' OR \"n\" < 0)", SyncValidationSql.violation(req("n", ColumnKind.COUNT)))
        assertEquals("(typeof(\"t\") <> 'text')", SyncValidationSql.violation(req("t", ColumnKind.TEXT)))
    }

    @Test
    fun typeViolationQueryCoversEveryColumnInOneScan() {
        val table = TransportSchema.SYNCED_TABLES.first { it.name == "feeds" }
        val sql = SyncValidationSql.typeViolationQuery(table)
        assertTrue(sql.startsWith("SELECT CASE "))
        assertTrue(sql.endsWith("FROM \"feeds\" WHERE ${table.columns.joinToString(" OR ") { SyncValidationSql.violation(it) }} LIMIT 1"))
        for (column in table.columnNames) assertTrue("THEN '$column'" in sql, column)
    }

    @Test
    fun missingTablesAndColumnsAreReportedAndExtraColumnsOnlyWhenRejected() {
        val expected = TransportSchema.EXPECTED_TABLES.getValue(1L)
        val complete = expected.associate { it.name to it.columnNames.toSet() }
        for (rejectExtra in listOf(true, false)) {
            assertNull(SyncValidationSql.columnsProblem(expected, complete, rejectExtra))
            // Tables the expectation does not name are allowed either way.
            assertNull(SyncValidationSql.columnsProblem(expected, complete + ("extra" to setOf("x")), rejectExtra))
            assertEquals("missing table ks_covered", SyncValidationSql.columnsProblem(expected, complete - "ks_covered", rejectExtra))
            assertEquals(
                "missing column feeds.favicon_url",
                SyncValidationSql.columnsProblem(expected, complete + ("feeds" to complete.getValue("feeds") - "favicon_url"), rejectExtra),
            )
            // Column names compare case-insensitively, like SQLite's.
            assertNull(SyncValidationSql.columnsProblem(expected, complete.mapValues { (_, c) -> c.map { it.uppercase() }.toSet() }, rejectExtra))
        }

        val extraColumn = complete + ("articles" to complete.getValue("articles") + "extra_body")
        assertEquals("unexpected column articles.extra_body", SyncValidationSql.columnsProblem(expected, extraColumn, rejectExtra = true))
        assertNull(SyncValidationSql.columnsProblem(expected, extraColumn, rejectExtra = false))
        val extraMetaColumn = complete + ("ks_meta" to complete.getValue("ks_meta") + "note")
        assertEquals("unexpected column ks_meta.note", SyncValidationSql.columnsProblem(expected, extraMetaColumn, rejectExtra = true))
    }

    @Test
    fun metaMatchingTheNameIsAccepted() {
        assertNull(SyncValidationSql.metaProblem(meta(delta), delta))
        assertNull(SyncValidationSql.metaProblem(meta(base), base))
    }

    @Test
    fun metaDisagreeingWithTheNameIsReported() {
        val k = TransportSchema.MetaKeys
        val cases = listOf(
            meta(delta) - k.CREATED_AT,
            meta(delta) + (k.GENERATION to "1f1e2d3c-4b5a-4968-8778-695a4b3c2d1e"),
            meta(delta) + (k.DEVICE to "b1b2c3d4-e5f6-4789-8abc-def012345678"),
            meta(delta) + (k.KIND to "base"),
            meta(delta) + (k.NUMBER to "8"),
            meta(delta) + (k.NUMBER to "07"),
            meta(delta) + (k.FORMAT to "0"),
            meta(delta) + (k.FORMAT to "one"),
            meta(delta) + (k.CREATED_AT to "-5"),
            meta(delta) + (k.CREATED_AT to "1001"), // not the name's creation time
        )
        for (case in cases) assertNotNull(SyncValidationSql.metaProblem(case, delta), case.toString())
    }

    @Test
    fun coverageRules() {
        val covered = TransportFileName(gen, TransportFileKind.DELTA, dev, 3, 1_000, "0123456789ab").fileName
        assertNull(SyncValidationSql.coverageProblem(delta, emptyList(), emptyList()))
        assertNotNull(SyncValidationSql.coverageProblem(delta, listOf(dev), emptyList()))
        assertNotNull(SyncValidationSql.coverageProblem(delta, emptyList(), listOf(covered)))

        assertNull(SyncValidationSql.coverageProblem(base, listOf(dev), listOf(covered)))
        assertNotNull(SyncValidationSql.coverageProblem(base, listOf("not-a-device"), emptyList()))
        assertNotNull(SyncValidationSql.coverageProblem(base, emptyList(), listOf("keryx.db.gz")))
        assertNotNull(SyncValidationSql.coverageProblem(base, emptyList(), listOf(base.fileName)))
        val otherGeneration = TransportFileName("1f1e2d3c-4b5a-4968-8778-695a4b3c2d1e", TransportFileKind.DELTA, dev, 1, 1_000, "0123456789ab")
        assertNotNull(SyncValidationSql.coverageProblem(base, emptyList(), listOf(otherGeneration.fileName)))
    }
}
