package works.merc.keryx.app.platform

import works.merc.keryx.app.domain.TransportFileKind
import works.merc.keryx.app.domain.TransportFixtures
import works.merc.keryx.app.domain.TransportFixtures.DEV_A
import works.merc.keryx.app.domain.TransportFixtures.GEN
import works.merc.keryx.app.domain.TransportFixtures.article
import works.merc.keryx.app.domain.TransportFixtures.deltaName
import works.merc.keryx.app.domain.TransportFixtures.exec
import works.merc.keryx.app.domain.TransportFixtures.feed
import works.merc.keryx.app.domain.TransportFixtures.feedTag
import works.merc.keryx.app.domain.TransportFixtures.folder
import works.merc.keryx.app.domain.TransportFixtures.setting
import works.merc.keryx.app.domain.TransportFixtures.tag
import works.merc.keryx.app.domain.TransportMeta
import java.io.File
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [DeltaExporter] against real file databases (JDBC), including the transaction that ties `S` to the rows. */
class DeltaExporterTest {

    private fun meta(seq: Long = 1, kind: TransportFileKind = TransportFileKind.DELTA) =
        TransportMeta(GEN, kind, DEV_A, seq, 1_000)

    private fun newPath(prefix: String = "ks-export-"): String =
        File.createTempFile(prefix, ".db").apply { delete(); deleteOnExit() }.absolutePath

    /** A local database with change capture on, then [rows] written (so recorded in the outbox). */
    private fun capturingDb(vararg rows: String): File {
        val local = TransportFixtures.localDb()
        exec(local, "UPDATE sync_capture SET enabled = 1", *rows)
        return local
    }

    private fun query(file: File, sql: String): List<List<String?>> = TransportFixtures.connect(file).use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList { while (rs.next()) add(List(rs.metaData.columnCount) { rs.getString(it + 1) }) }
            }
        }
    }

    private fun ids(file: String, table: String, key: String = "id"): List<String?> =
        query(File(file), "SELECT $key FROM $table ORDER BY $key").map { it[0] }

    private fun maxSeq(local: File): Long = query(local, "SELECT MAX(seq) FROM sync_outbox")[0][0]!!.toLong()

    private val seedRows = arrayOf(
        folder("d1", "Work"),
        folder("d2", "Unused"),
        tag("t1", "news"),
        feed("f1", folderId = "d1", folderAt = 100),
        feed("f2"),
        feedTag("f1", "t1"),
        article("a1", "f1", content = "<p>one</p>"),
        article("a2", "f1", content = "<p>two</p>"),
        setting("read_timeout_seconds", "30"),
    )

    @Test
    fun anEmptyOutboxExportsNothing() {
        val local = TransportFixtures.localDb(*seedRows) // capture off: nothing recorded
        val dest = newPath()
        File(dest).writeText("stale")
        assertNull(DeltaExporter.exportDelta(local.absolutePath, dest, meta()))
        assertFalse(File(dest).exists())
    }

    @Test
    fun aFirstDeltaCarriesEveryRecordedRowAndValidates() {
        val local = capturingDb(*seedRows)
        val dest = newPath()
        val export = assertNotNull(DeltaExporter.exportDelta(local.absolutePath, dest, meta()))
        assertEquals(maxSeq(local), export.highWaterSeq)
        assertEquals(TransportValidation.Valid, TransportFiles.validate(dest, ValidationTarget.Transport(deltaName(DEV_A, 1))))
        assertEquals(listOf("d1", "d2"), ids(dest, "folders"))
        assertEquals(listOf("f1", "f2"), ids(dest, "feeds"))
        assertEquals(listOf("a1", "a2"), ids(dest, "articles"))
        assertEquals(listOf("<p>one</p>", "<p>two</p>"), query(File(dest), "SELECT content FROM articles ORDER BY id").map { it[0] })
        assertEquals(
            mapOf(
                "folders" to 2L, "feeds" to 2L, "tags" to 1L, "articles" to 2L, "feed_tags" to 1L, "global_settings" to 1L,
                "folder_v2_edges" to 0L, "tag_v2_edges" to 0L,
            ),
            export.rowCounts,
        )
        // Nothing device-local, and no platform table, reaches the file.
        val tables = query(File(dest), "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").map { it[0] }
        assertEquals(
            listOf(
                "articles", "feed_tags", "feeds", "folder_v2_edges", "folders", "global_settings", "ks_covered", "ks_meta",
                "ks_vector", "tag_v2_edges", "tags",
            ),
            tables,
        )
    }

    @Test
    fun aLaterDeltaCarriesOnlyChangedRowsTheirReferencesAndLightArticles() {
        val local = capturingDb(*seedRows)
        exec(
            local,
            "DELETE FROM sync_outbox", // as if the first delta had been committed
            "UPDATE articles SET is_read = 1, read_at = 200 WHERE id = 'a1'", // light
            "UPDATE articles SET content = '<p>two, edited</p>', body_updated_at = 201 WHERE id = 'a2'", // heavy
            "UPDATE articles SET is_starred = 1, starred_at = 202 WHERE id = 'a2'", // light, but a2 has a heavy entry too
        )
        val dest = newPath()
        val export = assertNotNull(DeltaExporter.exportDelta(local.absolutePath, dest, meta(2)))
        assertEquals(maxSeq(local), export.highWaterSeq)
        assertEquals(TransportValidation.Valid, TransportFiles.validate(dest, ValidationTarget.Transport(deltaName(DEV_A, 2))))

        val rows = query(File(dest), "SELECT id, content, summary, is_read, is_starred, body_updated_at, title FROM articles ORDER BY id")
        assertEquals(listOf("a1", null, null, "1", "0", "100", "Article a1"), rows[0], "a1 is a light row with its stored body stamp")
        assertEquals(listOf("a2", "<p>two, edited</p>", null, "0", "1", "201", "Article a2"), rows[1])
        // The closure: the articles' feed and its folder — but not the untouched feed, folder, tag or link.
        assertEquals(listOf("f1"), ids(dest, "feeds"))
        assertEquals(listOf("d1"), ids(dest, "folders"))
        assertEquals(emptyList(), ids(dest, "tags"))
        assertEquals(emptyList(), ids(dest, "feed_tags", "feed_id"))
        assertEquals(emptyList(), ids(dest, "global_settings", "key"))
    }

    @Test
    fun aFeedTagEntryBringsItsFeedTagAndFolder() {
        val local = capturingDb(*seedRows)
        exec(local, "DELETE FROM sync_outbox", "UPDATE feed_tags SET deleted_at = 300, updated_at = 300 WHERE feed_id = 'f1'")
        val dest = newPath()
        assertNotNull(DeltaExporter.exportDelta(local.absolutePath, dest, meta(2)))
        assertEquals(TransportValidation.Valid, TransportFiles.validate(dest, ValidationTarget.Transport(deltaName(DEV_A, 2))))
        assertEquals(listOf(listOf("f1", "t1", "300")), query(File(dest), "SELECT feed_id, tag_id, deleted_at FROM feed_tags"))
        assertEquals(listOf("f1"), ids(dest, "feeds"))
        assertEquals(listOf("t1"), ids(dest, "tags"))
        assertEquals(listOf("d1"), ids(dest, "folders"))
        assertEquals(emptyList(), ids(dest, "articles"))
    }

    @Test
    fun aWriteCommittedAfterTheHighWaterMarkGoesIntoTheNextDelta() {
        val local = capturingDb(*seedRows)
        exec(local, "PRAGMA journal_mode = WAL", "DELETE FROM sync_outbox", "UPDATE articles SET is_read = 1, read_at = 200 WHERE id = 'a1'")
        val firstS = maxSeq(local)
        val dest = newPath()
        // Forced interleaving: another connection commits while the export's transaction is open,
        // between reading S and writing the rows.
        val export = assertNotNull(
            DeltaExporter.exportDelta(local.absolutePath, dest, meta(2)) {
                exec(
                    local,
                    "UPDATE articles SET title = 'Retitled', body_updated_at = 400 WHERE id = 'a1'",
                    article("a3", "f2", content = "late"),
                )
            },
        )
        assertEquals(firstS, export.highWaterSeq)
        // The file holds the snapshot as of S: no a3, and a1 still light with its old title.
        assertEquals(listOf(listOf("a1", null, "Article a1")), query(File(dest), "SELECT id, content, title FROM articles"))
        assertEquals(listOf("f1"), ids(dest, "feeds"))
        // The late writes are recorded above S ...
        assertTrue(query(local, "SELECT COUNT(*) FROM sync_outbox WHERE seq > $firstS")[0][0]!!.toInt() >= 2)

        // ... and once the first delta is committed, the next delta carries them.
        exec(local, "DELETE FROM sync_outbox WHERE seq <= ${export.highWaterSeq}")
        val next = newPath()
        val second = assertNotNull(DeltaExporter.exportDelta(local.absolutePath, next, meta(3)))
        assertTrue(second.highWaterSeq > firstS)
        assertEquals(
            listOf(listOf("a1", "<p>one</p>", "Retitled"), listOf("a3", "late", "Article a3")),
            query(File(next), "SELECT id, content, title FROM articles ORDER BY id"),
        )
        assertEquals(listOf("f1", "f2"), ids(next, "feeds"))
    }

    @Test
    fun inRollbackJournalModeAWriterWaitsForTheExportToFinish() {
        val local = capturingDb(*seedRows)
        exec(local, "DELETE FROM sync_outbox", "UPDATE articles SET is_read = 1, read_at = 200 WHERE id = 'a1'")
        val dest = newPath()
        var blocked: SQLException? = null
        assertNotNull(
            DeltaExporter.exportDelta(local.absolutePath, dest, meta(2)) {
                DriverManager.getConnection("jdbc:sqlite:${local.absolutePath}").use { conn ->
                    conn.createStatement().use { st ->
                        st.execute("PRAGMA busy_timeout = 50")
                        blocked = assertFailsWith<SQLException> { st.execute(article("a3", "f2", content = "late")) }
                    }
                }
            },
        )
        assertTrue(blocked!!.message.orEmpty().contains("SQLITE_BUSY"), blocked!!.message)
        assertEquals(listOf("a1"), ids(dest, "articles"))
    }

    @Test
    fun aDeltaMergedIntoAnotherDatabaseReproducesTheRows() {
        val local = capturingDb(*seedRows)
        val dest = newPath()
        assertNotNull(DeltaExporter.exportDelta(local.absolutePath, dest, meta()))
        val other = TransportFixtures.localDb()
        TransportFixtures.merge(other, File(dest))
        assertEquals(TransportFixtures.dump(local), TransportFixtures.dump(other))
    }

    @Test
    fun aDanglingReferenceFailsTheExportAndLeavesNoFile() {
        // The test database does not enforce foreign keys, so an article can reference a missing feed.
        val local = capturingDb(*seedRows, article("orphan", "no-such-feed", content = "x"))
        val dest = newPath()
        val failure = assertFailsWith<IllegalStateException> { DeltaExporter.exportDelta(local.absolutePath, dest, meta()) }
        assertTrue("articles.feed_id" in failure.message.orEmpty(), failure.message)
        assertFalse(File(dest).exists())
    }

    @Test
    fun aFullSendCarriesEveryRowWithItsBodyAndTheCopysHighWaterMark() {
        val local = capturingDb(*seedRows)
        exec(local, "UPDATE articles SET is_read = 1, read_at = 200 WHERE id = 'a1'")
        val copy = newPath("ks-copy-")
        DeltaExporter.copyForFullSend(local.absolutePath, copy)
        val dest = newPath()
        val export = DeltaExporter.exportFull(copy, dest, meta(), exceptPath = null)
        assertEquals(maxSeq(local), export.highWaterSeq)
        assertEquals(TransportValidation.Valid, TransportFiles.validate(dest, ValidationTarget.Transport(deltaName(DEV_A, 1))))
        assertEquals(TransportFixtures.dump(local), TransportFixtures.dump(File(dest)))
        assertEquals(listOf("<p>one</p>", "<p>two</p>"), query(File(dest), "SELECT content FROM articles ORDER BY id").map { it[0] })
    }

    @Test
    fun aFullSendOfAnEmptyOutboxStillWritesEveryRow() {
        val local = TransportFixtures.localDb(*seedRows)
        val copy = newPath("ks-copy-")
        DeltaExporter.copyForFullSend(local.absolutePath, copy)
        val dest = newPath()
        val export = DeltaExporter.exportFull(copy, dest, meta(), exceptPath = null)
        assertNull(export.highWaterSeq)
        assertEquals(2L, export.rowCounts["articles"])
    }

    @Test
    fun aFullSendLeavesOutRowsIdenticalToTheExceptFileButKeepsTheirReferences() {
        val local = capturingDb(*seedRows)
        // The base just merged: the same rows.
        val base = newPath("ks-base-")
        assertNotNull(DeltaExporter.exportDelta(local.absolutePath, base, meta()))
        // Then one article changes locally.
        exec(local, "UPDATE articles SET is_starred = 1, starred_at = 500 WHERE id = 'a2'")
        val copy = newPath("ks-copy-")
        DeltaExporter.copyForFullSend(local.absolutePath, copy)
        val dest = newPath()
        val export = DeltaExporter.exportFull(copy, dest, meta(2), exceptPath = base)
        assertEquals(TransportValidation.Valid, TransportFiles.validate(dest, ValidationTarget.Transport(deltaName(DEV_A, 2))))
        assertEquals(listOf("a2"), ids(dest, "articles"))
        // a2's feed and folder are identical to the base's, but still written for the closure.
        assertEquals(listOf("f1"), ids(dest, "feeds"))
        assertEquals(listOf("d1"), ids(dest, "folders"))
        assertEquals(emptyList(), ids(dest, "tags"))
        assertEquals(1L, export.rowCounts["articles"])
    }

    // ---- A feed's folder is sent as its raw assignment ----

    private fun feedFolderIn(file: String): List<String?> =
        query(File(file), "SELECT folder_id, folder_updated_at FROM feeds WHERE id = 'f1'").single()

    private fun outboxEntries(local: File): List<String> =
        query(local, "SELECT tbl || ':' || pk FROM sync_outbox ORDER BY seq").map { it[0]!! }

    /**
     * A folder's deletion and revival only re-resolve the feeds still assigned to it: the folder's own
     * change is recorded, but nothing for the feeds, whose sent (raw) value did not change. A change
     * of a feed's raw assignment is recorded as the feed's entry, and sent.
     */
    @Test
    fun aReResolutionAloneRecordsNothingAndARawChangeIsRecordedForTheFeed() {
        val local = capturingDb(*seedRows)
        exec(local, "DELETE FROM sync_outbox", "UPDATE folders SET deleted_at = 200, updated_at = 200 WHERE id = 'd1'")
        assertNull(query(local, "SELECT folder_id FROM feeds WHERE id = 'f1'").single()[0], "resolved to no folder")
        exec(local, "UPDATE folders SET deleted_at = NULL, updated_at = 300 WHERE id = 'd1'")
        assertEquals("d1", query(local, "SELECT folder_id FROM feeds WHERE id = 'f1'").single()[0])
        assertEquals(listOf("folders:d1", "folders:d1"), outboxEntries(local))

        exec(local, "DELETE FROM sync_outbox", "UPDATE ks_feed_folder_raw SET folder_id = 'd2', folder_updated_at = 400 WHERE feed_id = 'f1'")
        assertEquals(listOf("feeds:f1"), outboxEntries(local))
        val dest = newPath()
        assertNotNull(DeltaExporter.exportDelta(local.absolutePath, dest, meta()))
        assertEquals(listOf("d2", "400"), feedFolderIn(dest))
        assertEquals(listOf("d2"), ids(dest, "folders"))
    }

    /**
     * A feed assigned to a folder that is deleted here is sent with that raw assignment, not the
     * resolved "no folder" — and the closure carries the deleted folder's row — in a delta and in a
     * full send alike.
     */
    @Test
    fun aFeedInADeletedFolderIsSentWithItsRawAssignmentAndTheFoldersRow() {
        val local = capturingDb(*seedRows)
        exec(
            local,
            "UPDATE folders SET deleted_at = 200, updated_at = 200 WHERE id = 'd1'",
            "DELETE FROM sync_outbox",
            "UPDATE feeds SET title = 'Renamed', updated_at = 300 WHERE id = 'f1'",
        )
        assertEquals(listOf(null, "100"), query(local, "SELECT folder_id, folder_updated_at FROM feeds WHERE id = 'f1'").single())

        val delta = newPath()
        assertNotNull(DeltaExporter.exportDelta(local.absolutePath, delta, meta()))
        assertEquals(TransportValidation.Valid, TransportFiles.validate(delta, ValidationTarget.Transport(deltaName(DEV_A, 1))))
        assertEquals(listOf("d1", "100"), feedFolderIn(delta))
        assertEquals(listOf(listOf("d1", "200")), query(File(delta), "SELECT id, deleted_at FROM folders"))

        val copy = newPath("ks-copy-")
        DeltaExporter.copyForFullSend(local.absolutePath, copy)
        val full = newPath()
        DeltaExporter.exportFull(copy, full, meta(2), exceptPath = null)
        assertEquals(TransportValidation.Valid, TransportFiles.validate(full, ValidationTarget.Transport(deltaName(DEV_A, 2))))
        assertEquals(listOf("d1", "100"), feedFolderIn(full))
    }

    /**
     * A full send carries every identity edge of `ks_v2_edge`, split by kind — an edge of an id it holds
     * no row of included, and with an except file that carries none of them — while a delta carries none.
     */
    @Test
    fun aFullSendCarriesEveryIdentityEdgeAndADeltaNone() {
        val local = capturingDb(*seedRows)
        exec(
            local,
            "INSERT INTO ks_v2_edge (kind, v2_id, id) VALUES ('folder', 'p', 'd1')",
            "INSERT INTO ks_v2_edge (kind, v2_id, id) VALUES ('folder', 'p', 'd-elsewhere')",
            "INSERT INTO ks_v2_edge (kind, v2_id, id) VALUES ('tag', 'q', 't1')",
        )
        val delta = newPath()
        assertNotNull(DeltaExporter.exportDelta(local.absolutePath, delta, meta()))
        assertEquals(emptyList(), query(File(delta), "SELECT v2_id, id FROM folder_v2_edges"))
        assertEquals(emptyList(), query(File(delta), "SELECT v2_id, id FROM tag_v2_edges"))

        val copy = newPath("ks-copy-")
        DeltaExporter.copyForFullSend(local.absolutePath, copy)
        for (except in listOf(null, delta)) {
            val dest = newPath()
            DeltaExporter.exportFull(copy, dest, meta(2), exceptPath = except)
            assertEquals(TransportValidation.Valid, TransportFiles.validate(dest, ValidationTarget.Transport(deltaName(DEV_A, 2))))
            assertEquals(
                listOf(listOf("p", "d-elsewhere"), listOf("p", "d1")),
                query(File(dest), "SELECT v2_id, id FROM folder_v2_edges ORDER BY v2_id, id"),
            )
            assertEquals(listOf(listOf("q", "t1")), query(File(dest), "SELECT v2_id, id FROM tag_v2_edges"))
        }
    }

    /** A full send leaves out the edges its except file (the base just merged) already carries. */
    @Test
    fun aFullSendLeavesOutEdgesTheExceptFileCarries() {
        val local = capturingDb(*seedRows)
        val base = newPath("ks-base-")
        exec(File(base).also { TransportFiles.createEmpty(it.absolutePath, meta()) }, "INSERT INTO tag_v2_edges (v2_id, id) VALUES ('q', 't1')")
        exec(
            local,
            "INSERT INTO ks_v2_edge (kind, v2_id, id) VALUES ('tag', 'q', 't1')",
            "INSERT INTO ks_v2_edge (kind, v2_id, id) VALUES ('tag', 'q', 't2')",
        )
        val copy = newPath("ks-copy-")
        DeltaExporter.copyForFullSend(local.absolutePath, copy)
        val dest = newPath()
        DeltaExporter.exportFull(copy, dest, meta(2), exceptPath = base)
        assertEquals(listOf(listOf("q", "t2")), query(File(dest), "SELECT v2_id, id FROM tag_v2_edges"))
    }

    /**
     * A full send whose synced rows are all identical to the except file's still counts its edges, so it
     * is not taken for an empty file and left unsent.
     */
    @Test
    fun aFullSendOfOnlyEdgesIsNotEmpty() {
        val local = capturingDb(*seedRows)
        val base = newPath("ks-base-")
        assertNotNull(DeltaExporter.exportDelta(local.absolutePath, base, meta()))
        exec(local, "INSERT INTO ks_v2_edge (kind, v2_id, id) VALUES ('tag', 'q', 't1')")
        val copy = newPath("ks-copy-")
        DeltaExporter.copyForFullSend(local.absolutePath, copy)
        val export = DeltaExporter.exportFull(copy, newPath(), meta(2), exceptPath = base)
        assertEquals(0L, export.rowCounts["articles"])
        assertEquals(0L, export.rowCounts["folder_v2_edges"])
        assertEquals(1L, export.rowCounts["tag_v2_edges"])
        assertFalse(export.rowCounts.values.all { it == 0L })
    }

    @Test
    fun theCopyKeepsTheOutbox() {
        val local = capturingDb(*seedRows)
        val copy = newPath("ks-copy-")
        DeltaExporter.copyForFullSend(local.absolutePath, copy)
        assertEquals(maxSeq(local), maxSeq(File(copy)))
    }
}
