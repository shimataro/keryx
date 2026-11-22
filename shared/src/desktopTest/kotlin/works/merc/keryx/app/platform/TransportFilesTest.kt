package works.merc.keryx.app.platform

import works.merc.keryx.app.core.KS_DELTA_GRACE_MS
import works.merc.keryx.app.createSchemaV2
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.MergeSchema
import works.merc.keryx.app.domain.TableSpec
import works.merc.keryx.app.domain.TransportFileName
import works.merc.keryx.app.domain.TransportFixtures
import works.merc.keryx.app.domain.TransportFixtures.DEV_A
import works.merc.keryx.app.domain.TransportFixtures.DEV_B
import works.merc.keryx.app.domain.TransportFixtures.article
import works.merc.keryx.app.domain.TransportFixtures.baseName
import works.merc.keryx.app.domain.TransportFixtures.deltaName
import works.merc.keryx.app.domain.TransportFixtures.exec
import works.merc.keryx.app.domain.TransportFixtures.feed
import works.merc.keryx.app.domain.TransportFixtures.feedTag
import works.merc.keryx.app.domain.TransportFixtures.folder
import works.merc.keryx.app.domain.TransportFixtures.tag
import works.merc.keryx.app.domain.TransportFixtures.transportFile
import works.merc.keryx.app.domain.TransportSchema
import works.merc.keryx.app.fileDb
import works.merc.keryx.app.insertFeed
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [TransportFiles]: the transport DDL against its expectations, validation checks 4–9, and folding. */
class TransportFilesTest {

    private val delta = deltaName(DEV_A, 1)

    /** `PRAGMA table_info` rows as name → "TYPE|notnull|pk". */
    private fun tableInfo(file: File, table: String): Map<String, String> = TransportFixtures.connect(file).use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                buildMap { while (rs.next()) put(rs.getString("name"), "${rs.getString("type").uppercase()}|${rs.getInt("notnull")}|${rs.getInt("pk") > 0}") }
            }
        }
    }

    private fun expectedInfo(table: TableSpec, withPrimaryKey: Boolean = true): Map<String, String> =
        table.columns.associate { it.name to "${it.kind.declaredType}|${if (it.notNull) 1 else 0}|${withPrimaryKey && it.name in table.primaryKey}" }

    private fun validFile(name: TransportFileName = delta) = transportFile(
        name,
        folder("d1", "Work"),
        tag("t1", "news"),
        feed("f1", folderId = "d1", folderAt = 100),
        feedTag("f1", "t1"),
        article("a1", "f1", content = "body"),
    )

    // ---- The schema ----

    @Test
    fun theTransportDdlMatchesItsExpectations() {
        val file = transportFile(delta)
        for (table in TransportSchema.EXPECTED_TABLES.getValue(TransportSchema.VERSION)) {
            assertEquals(expectedInfo(table), tableInfo(file, table.name), table.name)
        }
        TransportFixtures.connect(file).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { it.next(); assertEquals(TransportSchema.VERSION, it.getLong(1)) }
            }
        }
    }

    @Test
    fun transportSyncedColumnsAreTheLocalSchemasSyncedColumnsWithoutSearchText() {
        val local = TransportFixtures.localDb()
        for (table in TransportSchema.SYNCED_TABLES_V1) {
            val localInfo = tableInfo(local, table.name)
            val expected = localInfo.filterKeys { it != "search_text" }
            // Same names, types and NOT NULL; the local primary keys match too.
            assertEquals(expected, expectedInfo(table), table.name)
        }
    }

    @Test
    fun mergeSchemaVersion3MatchesAFreshDatabase() {
        val local = TransportFixtures.localDb()
        for (table in MergeSchema.EXPECTED_TABLES.getValue(KeryxDatabase.Schema.version)) {
            assertEquals(tableInfo(local, table.name), expectedInfo(table), table.name)
        }
    }

    @Test
    fun mergeSchemaVersion2MatchesTheVersion2Schema() {
        val file = File.createTempFile("v2-", ".db").apply { deleteOnExit() }
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { it.createSchemaV2() }
        for (table in MergeSchema.EXPECTED_TABLES.getValue(2L)) {
            val actual = tableInfo(file, table.name)
            for (column in table.columns) {
                assertEquals(
                    "${column.kind.declaredType}|${if (column.notNull) 1 else 0}",
                    actual[column.name]?.substringBeforeLast('|'),
                    "${table.name}.${column.name}",
                )
            }
        }
    }

    // ---- Validation ----

    private fun validate(file: File, name: TransportFileName = delta) =
        TransportFiles.validate(file.absolutePath, works.merc.keryx.app.platform.ValidationTarget.Transport(name))

    private fun assertCorrupt(result: TransportValidation, mentioning: String) {
        assertIs<TransportValidation.Corrupt>(result)
        assertTrue(mentioning in result.reason, result.reason)
    }

    @Test
    fun aValidDeltaAndAValidBasePass() {
        assertEquals(TransportValidation.Valid, validate(validFile()))
        val base = baseName(DEV_B, 3)
        val file = validFile(base)
        exec(file, "INSERT INTO ks_vector (device, seq) VALUES ('$DEV_A', 4)", "INSERT INTO ks_covered (name) VALUES ('${deltaName(DEV_A, 4).fileName}')")
        assertEquals(TransportValidation.Valid, validate(file, base))
    }

    @Test
    fun aValidPreDeltaDatabasePasses() {
        val (file, driver, db) = fileDb()
        db.insertFeed("f1", now = 10)
        driver.close()
        exec(file, "PRAGMA user_version = ${KeryxDatabase.Schema.version}")
        assertEquals(TransportValidation.Valid, TransportFiles.validate(file.absolutePath, ValidationTarget.PreDelta(KeryxDatabase.Schema.version)))
    }

    @Test
    fun aVersion2PreDeltaDatabaseIsCheckedAgainstItsOwnSchema() {
        val file = File.createTempFile("v2-", ".db").apply { deleteOnExit() }
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { it.createSchemaV2() }
        exec(file, "PRAGMA user_version = 2")
        assertEquals(TransportValidation.Valid, TransportFiles.validate(file.absolutePath, ValidationTarget.PreDelta(KeryxDatabase.Schema.version)))
        exec(file, "INSERT INTO feeds (id, url, title, error_count, updated_at, created_at) VALUES ('f1', 'u', 't', 'many', 0, 0)")
        assertCorrupt(TransportFiles.validate(file.absolutePath, ValidationTarget.PreDelta(KeryxDatabase.Schema.version)), "feeds.error_count")
    }

    @Test
    fun textInAStampColumnIsCorrupt() {
        val file = validFile()
        exec(file, "UPDATE articles SET read_at = 'later'")
        assertCorrupt(validate(file), "articles.read_at")
    }

    @Test
    fun aRealInAStampColumnIsCorrupt() {
        val file = validFile()
        exec(file, "UPDATE feeds SET updated_at = 1.5")
        assertCorrupt(validate(file), "feeds.updated_at")
    }

    @Test
    fun aBooleanOtherThanZeroOrOneIsCorrupt() {
        val file = validFile()
        exec(file, "UPDATE articles SET is_starred = 2")
        assertCorrupt(validate(file), "articles.is_starred")
    }

    @Test
    fun anEmptyIdIsCorrupt() {
        val file = validFile()
        exec(file, "INSERT INTO tags (id, name, sort_order, updated_at, created_at) VALUES ('', 'x', 0, 0, 0)")
        assertCorrupt(validate(file), "tags.id")
    }

    @Test
    fun aNullInANotNullColumnIsCorrupt() {
        // SQLite enforces the transport file's own NOT NULL, so the writer must have declared it laxer.
        val file = validFile()
        exec(
            file,
            "DROP TABLE global_settings",
            "CREATE TABLE global_settings (key TEXT NOT NULL PRIMARY KEY, value TEXT, updated_at INTEGER NOT NULL)",
            "INSERT INTO global_settings (key, value, updated_at) VALUES ('k', NULL, 1)",
        )
        assertCorrupt(validate(file), "global_settings.value")
    }

    @Test
    fun aMissingColumnOrTableIsCorrupt() {
        val noColumn = validFile()
        exec(noColumn, "ALTER TABLE feeds DROP COLUMN favicon_url")
        assertCorrupt(validate(noColumn), "feeds.favicon_url")

        val noTable = validFile()
        exec(noTable, "DROP TABLE ks_covered")
        assertCorrupt(validate(noTable), "ks_covered")
    }

    @Test
    fun anExtraColumnInATransportFileIsCorrupt() {
        // A writer adding a column bumps the transport version; folding would silently drop it.
        val file = validFile()
        exec(file, "ALTER TABLE articles ADD COLUMN extra_body TEXT")
        assertCorrupt(validate(file), "unexpected column articles.extra_body")
    }

    @Test
    fun anExtraGeneratedColumnInATransportFileIsCorrupt() {
        // PRAGMA table_info leaves generated columns out; the check must still see them.
        val file = validFile()
        exec(file, "ALTER TABLE articles ADD COLUMN extra_title TEXT GENERATED ALWAYS AS (title) VIRTUAL")
        assertCorrupt(validate(file), "unexpected column articles.extra_title")
    }

    @Test
    fun anExtraColumnInAPreDeltaDatabaseIsAccepted() {
        // A pre-delta database is a full local database, with device-local columns of its own.
        val (file, driver, db) = fileDb()
        db.insertFeed("f1", now = 10)
        driver.close()
        exec(file, "ALTER TABLE articles ADD COLUMN extra_body TEXT", "PRAGMA user_version = ${KeryxDatabase.Schema.version}")
        assertEquals(TransportValidation.Valid, TransportFiles.validate(file.absolutePath, ValidationTarget.PreDelta(KeryxDatabase.Schema.version)))
    }

    @Test
    fun metaThatDisagreesWithTheNameIsCorrupt() {
        val file = validFile()
        assertCorrupt(validate(file, deltaName(DEV_B, 1)), "ks_meta.device")
        assertCorrupt(validate(file, deltaName(DEV_A, 2)), "ks_meta.number")
        assertCorrupt(validate(file, baseName(DEV_A, 1)), "ks_meta.kind")
        assertCorrupt(validate(file, deltaName(DEV_A, 1, createdAt = 1_001)), "ks_meta.created_at")
        exec(file, "DELETE FROM ks_meta WHERE key = 'created_at'")
        assertCorrupt(validate(file), "created_at")
    }

    @Test
    fun aDeltaCarryingCoverageIsCorrupt() {
        val file = validFile()
        exec(file, "INSERT INTO ks_vector (device, seq) VALUES ('$DEV_A', 1)")
        assertCorrupt(validate(file), "ks_vector")
    }

    @Test
    fun aBaseCoveringAMalformedNameIsCorrupt() {
        val base = baseName(DEV_A, 1)
        val file = validFile(base)
        exec(file, "INSERT INTO ks_covered (name) VALUES ('keryx.db.gz')")
        assertCorrupt(validate(file, base), "ks_covered")
    }

    @Test
    fun everyKindOfDanglingReferenceIsCorrupt() {
        val cases = mapOf(
            "feeds.folder_id" to "DELETE FROM folders",
            "articles.feed_id" to "UPDATE articles SET feed_id = 'nowhere'",
            "feed_tags.feed_id" to "UPDATE feed_tags SET feed_id = 'nowhere'",
            "feed_tags.tag_id" to "DELETE FROM tags",
        )
        for ((description, sql) in cases) {
            val file = validFile()
            exec(file, sql)
            assertCorrupt(validate(file), description)
        }
    }

    @Test
    fun aNewerVersionIsTooNew() {
        val file = validFile()
        exec(file, "PRAGMA user_version = ${TransportSchema.VERSION + 1}")
        assertEquals(TransportValidation.TooNew(TransportSchema.VERSION + 1), validate(file))

        val (full, driver, _) = fileDb()
        driver.close()
        exec(full, "PRAGMA user_version = 1000")
        assertEquals(TransportValidation.TooNew(1000), TransportFiles.validate(full.absolutePath, ValidationTarget.PreDelta(KeryxDatabase.Schema.version)))
    }

    @Test
    fun anUnregisteredVersionIsUndeterminedNeverCorrupt() {
        val file = validFile()
        exec(file, "PRAGMA user_version = 0")
        assertIs<TransportValidation.Undetermined>(validate(file))
    }

    @Test
    fun aMissingFileIsUndeterminedAndGarbageIsCorrupt() {
        val missing = File.createTempFile("missing-", ".db").apply { delete() }
        assertIs<TransportValidation.Undetermined>(validate(missing))
        assertTrue(!missing.exists(), "validation must not create the file")

        val garbage = File.createTempFile("garbage-", ".db").apply { deleteOnExit(); writeBytes(ByteArray(8192) { 0x5A }) }
        assertIs<TransportValidation.Corrupt>(validate(garbage))
    }

    @Test
    fun validationLeavesTheFileUnchanged() {
        val file = validFile()
        val before = file.readBytes()
        validate(file)
        assertTrue(before.contentEquals(file.readBytes()))
    }

    // ---- Folding ----

    @Test
    fun foldingDeltasRecordsThemAndExtendsTheVectorContiguously() {
        val acc = TransportFixtures.accumulator()
        for (seq in listOf(1L, 2L, 4L)) {
            val name = deltaName(DEV_A, seq)
            TransportFiles.fold(acc.absolutePath, transportFile(name).absolutePath, name)
        }
        val coverage = TransportFixtures.dump(acc, coverage = true)
        assertEquals(listOf("$DEV_A|2"), coverage.getValue("ks_vector"))
        // Each covered delta carries its creation time (the fixture's ks_meta.created_at).
        assertEquals(listOf(1L, 2L, 4L).map { "${deltaName(DEV_A, it).fileName}|1000" }.sorted(), coverage.getValue("ks_covered"))

        // A base whose vector covers delta 3 closes the gap: the vector then extends over 4.
        val base = baseName(DEV_B, 1)
        val baseFile = transportFile(base, "INSERT INTO ks_vector (device, seq) VALUES ('$DEV_A', 3)")
        TransportFiles.fold(acc.absolutePath, baseFile.absolutePath, base)
        assertEquals(listOf("$DEV_A|4"), TransportFixtures.dump(acc, coverage = true).getValue("ks_vector"))
        assertEquals(TransportValidation.Valid, TransportFiles.validate(acc.absolutePath, ValidationTarget.Transport(baseName(DEV_A, 1))))
    }

    @Test
    fun aFoldKeepsTheEarliestKnownCreationTimeOfACoveredDelta() {
        val acc = TransportFixtures.accumulator()
        val delta = deltaName(DEV_A, 1)
        val base = baseName(DEV_B, 1)
        val baseFile = transportFile(base, "INSERT INTO ks_covered (name, created_at) VALUES ('${delta.fileName}', 400)")
        TransportFiles.fold(acc.absolutePath, transportFile(delta).absolutePath, delta, extendVector = false)
        TransportFiles.fold(acc.absolutePath, baseFile.absolutePath, base, extendVector = false)
        assertEquals(listOf("${delta.fileName}|400"), TransportFixtures.dump(acc, coverage = true).getValue("ks_covered"))

        val unknown = baseName(DEV_B, 2)
        val unknownFile = transportFile(unknown, "INSERT INTO ks_covered (name) VALUES ('${delta.fileName}')")
        TransportFiles.fold(acc.absolutePath, unknownFile.absolutePath, unknown, extendVector = false)
        assertEquals(listOf("${delta.fileName}|400"), TransportFixtures.dump(acc, coverage = true).getValue("ks_covered"), "an unknown time never replaces a known one")
    }

    @Test
    fun finalizeBaseExtendsTheVectorOnlyOverOldDeltasAndListsOnlyWhatItDoesNotImply() {
        // The base's own creation time sets the impliable cutoff: 2000.
        val acc = TransportFixtures.accumulator(createdAt = 2_000 + KS_DELTA_GRACE_MS)
        // Deltas 1 and 2 were created at 1000 (old enough), delta 3 at 5000 (too recent), delta 5 past a gap.
        for (seq in listOf(1L, 2L)) {
            val name = deltaName(DEV_A, seq)
            TransportFiles.fold(acc.absolutePath, transportFile(name).absolutePath, name, extendVector = false)
        }
        for (seq in listOf(3L, 5L)) {
            val name = deltaName(DEV_A, seq, createdAt = 5_000)
            TransportFiles.fold(acc.absolutePath, transportFile(name).absolutePath, name, extendVector = false)
        }

        TransportFiles.finalizeBase(acc.absolutePath)

        val coverage = TransportFixtures.dump(acc, coverage = true)
        assertEquals(listOf("$DEV_A|2"), coverage.getValue("ks_vector"))
        assertEquals(listOf(3L, 5L).map { "${deltaName(DEV_A, it, createdAt = 5_000).fileName}|5000" }, coverage.getValue("ks_covered"))
    }

    @Test
    fun finalizeBaseKeepsEveryDuplicateOfACloneListed() {
        val acc = TransportFixtures.accumulator(createdAt = 2_000 + KS_DELTA_GRACE_MS)
        val first = deltaName(DEV_A, 1)
        val duplicate = first.copy(hash12 = "ba9876543210")
        TransportFiles.fold(acc.absolutePath, transportFile(first).absolutePath, first, extendVector = false)
        TransportFiles.fold(acc.absolutePath, transportFile(duplicate).absolutePath, duplicate, extendVector = false)

        TransportFiles.finalizeBase(acc.absolutePath)

        val coverage = TransportFixtures.dump(acc, coverage = true)
        assertEquals(listOf("$DEV_A|1"), coverage.getValue("ks_vector"))
        assertEquals(listOf(first, duplicate).map { "${it.fileName}|1000" }.sorted(), coverage.getValue("ks_covered"))
    }

    @Test
    fun foldingRefusesAFileOfAnotherVersion() {
        val acc = TransportFixtures.accumulator()
        val input = transportFile(delta)
        exec(input, "PRAGMA user_version = 0")
        assertFailsWith<IllegalStateException> { TransportFiles.fold(acc.absolutePath, input.absolutePath, delta) }
    }

    @Test
    fun createEmptyReplacesAnExistingFile() {
        val file = validFile()
        TransportFiles.createEmpty(file.absolutePath, works.merc.keryx.app.domain.TransportMeta(TransportFixtures.GEN, delta.kind, DEV_A, 1, delta.createdAt))
        assertTrue(TransportFixtures.dump(file).values.all { it.isEmpty() })
        assertEquals(TransportValidation.Valid, validate(file))
    }
}
