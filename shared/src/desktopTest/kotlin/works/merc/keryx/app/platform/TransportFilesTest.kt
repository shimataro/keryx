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
        for (table in TransportSchema.SYNCED_TABLES) {
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

    /** A file of version 1, as an earlier build wrote it: no `v2_id` columns and no identity edge tables (`feed_edges` included). */
    private fun versionOneFile(name: TransportFileName = delta): File = validFile(name).also { file ->
        exec(
            file,
            "DROP TABLE folder_v2_edges",
            "DROP TABLE tag_v2_edges",
            "DROP TABLE feed_edges",
            "ALTER TABLE folders DROP COLUMN v2_id",
            "ALTER TABLE tags DROP COLUMN v2_id",
            "PRAGMA user_version = 1",
        )
    }

    @Test
    fun aVersionOneFileIsValidAndMigratedToTheCurrentSchema() {
        val file = versionOneFile()
        assertEquals(TransportValidation.Valid, validate(file))
        // Validation migrated it in place: every table of the current version, the edge tables included.
        for (table in TransportSchema.EXPECTED_TABLES.getValue(TransportSchema.VERSION)) {
            assertEquals(expectedInfo(table), tableInfo(file, table.name), table.name)
        }
        assertEquals(true, TransportFiles.schemaMatches(file.absolutePath))
    }

    @Test
    fun prepareForMergeCreatesTheIdentityEdgeTablesOfAVersionOneFile() {
        val file = versionOneFile()
        TransportFiles.prepareForMerge(file.absolutePath)
        for (table in TransportSchema.EDGE_TABLES) {
            assertEquals(expectedInfo(table), tableInfo(file, table.name), table.name)
        }
        assertEquals(true, TransportFiles.schemaMatches(file.absolutePath))
    }

    @Test
    fun aFileWithIdentityEdgesIsValidAndOneWithoutTheTablesIsCorrupt() {
        val file = validFile()
        exec(
            file,
            "INSERT INTO folder_v2_edges (v2_id, id) VALUES ('p', 'd1')",
            // An edge needs no row of its id: it is not a reference.
            "INSERT INTO tag_v2_edges (v2_id, id) VALUES ('q', 't-elsewhere')",
        )
        assertEquals(TransportValidation.Valid, validate(file))

        val missing = validFile()
        exec(missing, "DROP TABLE tag_v2_edges")
        assertCorrupt(validate(missing), "tag_v2_edges")

        val emptyId = validFile()
        exec(emptyId, "INSERT INTO folder_v2_edges (v2_id, id) VALUES ('p', '')")
        assertCorrupt(validate(emptyId), "folder_v2_edges.id")
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

    /**
     * A transport file's feed folder reference is satisfied by a folder row of the file or one reached
     * from it through the file's identity edges (either direction, transitively). A reference no row
     * and no edge path reaches is a dangling reference.
     */
    @Test
    fun aFeedFolderReferenceIsSatisfiedByAFolderRowReachedThroughEdges() {
        fun file(folderId: String, vararg extra: String) = transportFile(
            delta, folder("d-a", "A"), feed("f1", folderId = folderId, folderAt = 100), *extra,
        )
        fun edge(table: String, v2Id: String, id: String) = "INSERT INTO $table (v2_id, id) VALUES ('$v2Id', '$id')"
        // No row, no edge: dangling.
        assertCorrupt(validate(file("X")), "feeds.folder_id")
        // Edges that reach no row of the file: still dangling.
        assertCorrupt(validate(file("X", edge("folder_v2_edges", "p", "X"), edge("folder_v2_edges", "p", "Y"))), "feeds.folder_id")
        // Reaching the row directly.
        assertEquals(
            TransportValidation.Valid,
            validate(file("X", edge("folder_v2_edges", "p", "X"), edge("folder_v2_edges", "p", "d-a"))),
        )
        // Transitively: X - p - Y - q - d-a.
        assertEquals(
            TransportValidation.Valid,
            validate(
                file(
                    "X",
                    edge("folder_v2_edges", "p", "X"), edge("folder_v2_edges", "p", "Y"),
                    edge("folder_v2_edges", "q", "Y"), edge("folder_v2_edges", "q", "d-a"),
                ),
            ),
        )
        // The row's id referenced from the other end: the reference is the canonical id's alias row.
        assertEquals(
            TransportValidation.Valid,
            validate(file("d-a", edge("folder_v2_edges", "p", "d-a"))),
        )
        // A tag's edges do not stand in for a folder's.
        assertCorrupt(
            validate(file("X", edge("tag_v2_edges", "p", "X"), edge("tag_v2_edges", "p", "d-a"))),
            "feeds.folder_id",
        )
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
    fun finalizeBaseKeepsAYoungCloneListedWhereAnInputBaseAlreadyImpliesItsSeq() {
        val acc = TransportFixtures.accumulator(createdAt = 2_000 + KS_DELTA_GRACE_MS)
        // Input base A implies delta 1 of DEV_A; another input names a young copy B of that same (device, seq).
        val baseA = baseName(DEV_B, 1)
        val fileA = transportFile(baseA, "INSERT INTO ks_vector (device, seq) VALUES ('$DEV_A', 1)")
        val young = deltaName(DEV_A, 1, hash = "ba9876543210", createdAt = 5_000)
        val other = baseName(DEV_B, 2)
        val otherFile = transportFile(other, "INSERT INTO ks_covered (name, created_at) VALUES ('${young.fileName}', ${young.createdAt})")
        TransportFiles.fold(acc.absolutePath, fileA.absolutePath, baseA, extendVector = false)
        TransportFiles.fold(acc.absolutePath, otherFile.absolutePath, other, extendVector = false)

        TransportFiles.finalizeBase(acc.absolutePath)

        val coverage = TransportFixtures.dump(acc, coverage = true)
        assertEquals(listOf("$DEV_A|1"), coverage.getValue("ks_vector"))
        assertEquals(listOf("${young.fileName}|5000"), coverage.getValue("ks_covered"), "the vector does not imply a copy younger than the cutoff")
        val base = TransportFiles.readCoverage(acc.absolutePath, baseName(DEV_A, 1, createdAt = 2_000 + KS_DELTA_GRACE_MS))
        assertTrue(base.covers(young))
    }

    @Test
    fun finalizeBaseKeepsANameOfUnknownAgeListed() {
        val acc = TransportFixtures.accumulator(createdAt = 2_000 + KS_DELTA_GRACE_MS)
        val baseA = baseName(DEV_B, 1)
        val fileA = transportFile(baseA, "INSERT INTO ks_vector (device, seq) VALUES ('$DEV_A', 1)")
        val unknown = deltaName(DEV_A, 1, hash = "ba9876543210")
        val other = baseName(DEV_B, 2)
        val otherFile = transportFile(other, "INSERT INTO ks_covered (name) VALUES ('${unknown.fileName}')")
        TransportFiles.fold(acc.absolutePath, fileA.absolutePath, baseA, extendVector = false)
        TransportFiles.fold(acc.absolutePath, otherFile.absolutePath, other, extendVector = false)

        TransportFiles.finalizeBase(acc.absolutePath)

        assertEquals(listOf("${unknown.fileName}|null"), TransportFixtures.dump(acc, coverage = true).getValue("ks_covered"))
    }

    @Test
    fun foldingRefusesAFileOfAnotherVersion() {
        val acc = TransportFixtures.accumulator()
        val input = transportFile(delta)
        exec(input, "PRAGMA user_version = 0")
        assertFailsWith<IllegalStateException> { TransportFiles.fold(acc.absolutePath, input.absolutePath, delta) }
    }

    @Test
    fun foldingKeepsNonNullV2IdForFoldersRegardlessOfInputOrder() {
        val id = "d1"
        val withV2 = transportFile(delta, folder(id = id, name = "Work", v2Id = "legacy-1"))
        val withoutV2 = transportFile(deltaName(DEV_B, 1), folder(id = id, name = "Work"))

        fun v2Id(file: File): String? = TransportFixtures.connect(file).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT v2_id FROM folders WHERE id = '$id'").use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }

        assertEquals("legacy-1", v2Id(TransportFixtures.fold(withV2 to delta, withoutV2 to deltaName(DEV_B, 1))))
        assertEquals("legacy-1", v2Id(TransportFixtures.fold(withoutV2 to deltaName(DEV_B, 1), withV2 to delta)))
    }

    @Test
    fun foldingKeepsNonNullV2IdForTagsRegardlessOfInputOrder() {
        val id = "t1"
        val withV2 = transportFile(delta, tag(id = id, name = "news", v2Id = "legacy-1"))
        val withoutV2 = transportFile(deltaName(DEV_B, 1), tag(id = id, name = "news"))

        fun v2Id(file: File): String? = TransportFixtures.connect(file).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT v2_id FROM tags WHERE id = '$id'").use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }

        assertEquals("legacy-1", v2Id(TransportFixtures.fold(withV2 to delta, withoutV2 to deltaName(DEV_B, 1))))
        assertEquals("legacy-1", v2Id(TransportFixtures.fold(withoutV2 to deltaName(DEV_B, 1), withV2 to delta)))
    }

    // ---- Feed identity edges (feed_edges) ----

    private fun feedEdges(file: File): Set<String> = TransportFixtures.connect(file).use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT a, b FROM feed_edges").use { rs ->
                buildSet { while (rs.next()) add("${rs.getString(1)}|${rs.getString(2)}") }
            }
        }
    }

    private fun feedEdgeCount(file: File): Int = TransportFixtures.connect(file).use { conn ->
        conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM feed_edges").use { rs -> rs.next(); rs.getInt(1) } }
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items) else items.flatMap { first -> permutations(items - first).map { listOf(first) + it } }

    private val deltaB = deltaName(DEV_B, 1)
    private val deltaC = deltaName(TransportFixtures.DEV_C, 1)

    @Test
    fun foldingRecordsFeedEdgesAsAUnionNormalizedAndWithoutDuplicates() {
        val one = transportFile(delta, "INSERT INTO feed_edges (a, b) VALUES ('fa', 'fb')")
        val two = transportFile(
            deltaB,
            "INSERT INTO feed_edges (a, b) VALUES ('fa', 'fb')",
            "INSERT INTO feed_edges (a, b) VALUES ('fc', 'fd')",
            // Written the wrong way round and as a self-edge: a fold normalizes the one and drops the other.
            "INSERT INTO feed_edges (a, b) VALUES ('fz', 'fy')",
            "INSERT INTO feed_edges (a, b) VALUES ('fq', 'fq')",
        )
        val folded = TransportFixtures.fold(one to delta, two to deltaB)
        assertEquals(setOf("fa|fb", "fc|fd", "fy|fz"), feedEdges(folded))
        assertEquals(3, feedEdgeCount(folded))
        TransportFiles.fold(folded.absolutePath, two.absolutePath, deltaB)
        assertEquals(3, feedEdgeCount(folded))
        assertEquals(TransportValidation.Valid, validate(folded, baseName(DEV_A, 1)))
    }

    @Test
    fun foldingTwoFilesWhoseFeedsShareAUrlUnderDifferentIdsRecordsTheEdge() {
        val one = transportFile(delta, feed("fb", url = "https://x/"))
        val two = transportFile(deltaB, feed("fa", url = "https://x/"))
        for (inputs in listOf(listOf(one to delta, two to deltaB), listOf(two to deltaB, one to delta))) {
            assertEquals(setOf("fa|fb"), feedEdges(TransportFixtures.fold(*inputs.toTypedArray())))
        }
    }

    @Test
    fun foldingAFileWhoseOwnFeedsShareAUrlRecordsTheEdge() {
        val file = transportFile(delta, feed("fb", url = "https://x/"), feed("fa", url = "https://x/"), feed("fc", url = "https://y/"))
        assertEquals(setOf("fa|fb"), feedEdges(TransportFixtures.fold(file to delta)))
    }

    @Test
    fun aFeedWithTheSameUrlAndTheSameIdIsNoEdge() {
        val one = transportFile(delta, feed("fa", url = "https://x/"))
        val two = transportFile(deltaB, feed("fa", url = "https://x/", updatedAt = 200))
        assertEquals(emptySet(), feedEdges(TransportFixtures.fold(one to delta, two to deltaB)))
    }

    /** A URL collision that involves no URL move is recorded in every fold order, two files or three. */
    @Test
    fun aUrlCollisionWithoutAUrlMoveIsRecordedInEveryFoldOrder() {
        val two = listOf(
            transportFile(delta, feed("fa", url = "https://x/")) to delta,
            transportFile(deltaB, feed("fb", url = "https://x/")) to deltaB,
        )
        for (order in permutations(two.indices.toList())) {
            assertEquals(setOf("fa|fb"), feedEdges(TransportFixtures.fold(*order.map { two[it] }.toTypedArray())), "fold order $order")
        }
        val three = listOf(
            transportFile(delta, feed("fa", url = "https://x/", updatedAt = 100)) to delta,
            transportFile(deltaB, feed("fb", url = "https://x/", updatedAt = 150)) to deltaB,
            transportFile(deltaC, feed("fa", url = "https://x/", updatedAt = 200), feed("fc", url = "https://y/")) to deltaC,
        )
        for (order in permutations(three.indices.toList())) {
            assertEquals(setOf("fa|fb"), feedEdges(TransportFixtures.fold(*order.map { three[it] }.toTypedArray())), "fold order $order")
        }
    }

    /**
     * DOCUMENTED LIMITATION: a collision that involves a URL move is not guaranteed to be seen. F1 (fa@X),
     * F3 (fa@Y, newer), F2 (fb@X): folding F1, F2, F3 sees fa and fb share X; folding F1, F3, F2 has moved fa
     * to Y by the time F2 arrives, so no edge is recorded (a merge one file at a time behaves the same).
     * What is guaranteed is only that every edge a device observes is sent and unioned, so the final state
     * depends on the edge set alone. This test pins that limit down.
     */
    @Test
    fun aUrlCollisionInvolvingAUrlMoveIsSeenOnlyIfTheFoldMeetsItBeforeTheMove_documentedLimitation() {
        val f1 = transportFile(delta, feed("fa", url = "https://x/", updatedAt = 100)) to delta
        val f2 = transportFile(deltaB, feed("fb", url = "https://x/", updatedAt = 150)) to deltaB
        val f3 = transportFile(deltaC, feed("fa", url = "https://y/", updatedAt = 200)) to deltaC
        assertEquals(setOf("fa|fb"), feedEdges(TransportFixtures.fold(f1, f2, f3)))
        assertEquals(emptySet(), feedEdges(TransportFixtures.fold(f1, f3, f2)))
    }

    /**
     * Whatever a fold sees or misses of a collision, merging its result equals merging its inputs one at a
     * time in the same order — the three files of the limitation above included, in every order, nested or
     * not. (Different orders may end differently only where a collision is seen in one and missed in the
     * other, which is the limit itself.)
     */
    @Test
    fun mergingAFoldEqualsMergingItsInputsOneByOneInTheSameOrder() {
        val files = listOf(
            transportFile(delta, feed("fa", url = "https://x/", updatedAt = 100)) to delta,
            transportFile(deltaB, feed("fa", url = "https://y/", updatedAt = 200)) to deltaB,
            transportFile(deltaC, feed("fb", url = "https://x/", updatedAt = 150)) to deltaC,
        )
        for (order in permutations(files.indices.toList())) {
            val ordered = order.map { files[it] }
            val oneByOne = TransportFixtures.localDb().also { local -> ordered.forEach { (file, _) -> TransportFixtures.merge(local, file) } }
            val viaFold = TransportFixtures.localDb().also { TransportFixtures.merge(it, TransportFixtures.fold(*ordered.toTypedArray())) }
            assertEquals(TransportFixtures.identityState(oneByOne), TransportFixtures.identityState(viaFold), "order $order")
            // Nested: the first two folded, then folded with the third.
            val nested = TransportFixtures.fold(TransportFixtures.fold(*ordered.take(2).toTypedArray()) to baseName(DEV_A, 1), ordered[2])
            val viaNested = TransportFixtures.localDb().also { TransportFixtures.merge(it, nested) }
            assertEquals(TransportFixtures.identityState(viaFold), TransportFixtures.identityState(viaNested), "nested, order $order")
        }
    }

    /** The feed edges of a fold do not depend on the order, the grouping, or on folding again. */
    @Test
    fun feedEdgesFoldCommutativelyAssociativelyAndIdempotently() {
        val files = listOf(
            transportFile(
                delta,
                "INSERT INTO feed_edges (a, b) VALUES ('f1', 'f2')",
                feed("f3", url = "https://x/"),
            ) to delta,
            transportFile(
                deltaB,
                "INSERT INTO feed_edges (a, b) VALUES ('f2', 'f4')",
                feed("f5", url = "https://x/"),
                feed("f6", url = "https://z/", updatedAt = 100),
            ) to deltaB,
            transportFile(
                deltaC,
                "INSERT INTO feed_edges (a, b) VALUES ('f1', 'f2')",
                feed("f6", url = "https://w/", updatedAt = 200),
                feed("f7", url = "https://w/"),
            ) to deltaC,
        )
        val results = permutations(files.indices.toList()).map { order ->
            val folded = TransportFixtures.fold(*order.map { files[it] }.toTypedArray())
            feedEdges(folded)
        }
        val expected = setOf("f1|f2", "f2|f4", "f3|f5", "f6|f7")
        // f6@z then f6@w then f7@w: only the orders folding f7 after f6's move to w see f6|f7 — but the
        // fold must still agree across orders on everything it was told explicitly or by a visible collision.
        for (edges in results) assertTrue(edges.containsAll(setOf("f1|f2", "f2|f4", "f3|f5")), "$edges")
        assertTrue(results.all { it == expected }, "every order yields $expected, got ${results.toSet()}")

        // Idempotent: folding an input again, or folding the result, changes nothing.
        val folded = TransportFixtures.fold(*files.toTypedArray())
        files.forEach { (file, name) -> TransportFiles.fold(folded.absolutePath, file.absolutePath, name) }
        assertEquals(expected, feedEdges(folded))
        assertEquals(expected, feedEdges(TransportFixtures.fold(folded to baseName(DEV_A, 1))))
    }

    @Test
    fun aVersionTwoFileIsMigratedWithAnEmptyFeedEdgesTableAndValidates() {
        val file = validFile()
        exec(file, "DROP TABLE feed_edges", "PRAGMA user_version = 2")
        assertEquals(TransportValidation.Valid, validate(file))
        assertEquals(expectedInfo(TransportSchema.EDGE_TABLES.first { it.name == "feed_edges" }), tableInfo(file, "feed_edges"))
        assertEquals(emptySet(), feedEdges(file))
        assertEquals(true, TransportFiles.schemaMatches(file.absolutePath))
    }

    @Test
    fun aVersionThreeFileWithoutFeedEdgesOrWithAWrongColumnSetIsCorrupt() {
        val missing = validFile()
        exec(missing, "DROP TABLE feed_edges")
        assertCorrupt(validate(missing), "feed_edges")

        val extra = validFile()
        exec(extra, "ALTER TABLE feed_edges ADD COLUMN extra TEXT")
        assertCorrupt(validate(extra), "unexpected column feed_edges.extra")

        val lacking = validFile()
        exec(lacking, "DROP TABLE feed_edges", "CREATE TABLE feed_edges (a TEXT NOT NULL, PRIMARY KEY (a))")
        assertCorrupt(validate(lacking), "feed_edges.b")

        val emptyId = validFile()
        exec(emptyId, "INSERT INTO feed_edges (a, b) VALUES ('fa', '')")
        assertCorrupt(validate(emptyId), "feed_edges.b")
    }

    @Test
    fun createEmptyReplacesAnExistingFile() {
        val file = validFile()
        TransportFiles.createEmpty(file.absolutePath, works.merc.keryx.app.domain.TransportMeta(TransportFixtures.GEN, delta.kind, DEV_A, 1, delta.createdAt))
        assertTrue(TransportFixtures.dump(file).values.all { it.isEmpty() })
        assertEquals(TransportValidation.Valid, validate(file))
    }
}
