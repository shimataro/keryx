package works.merc.keryx.app.platform

import works.merc.keryx.app.domain.TransportFileKind
import works.merc.keryx.app.domain.TransportFileName
import works.merc.keryx.app.domain.TransportMeta
import works.merc.keryx.app.domain.TransportSchema
import works.merc.keryx.app.tempFilePath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [DeltaExporter] through each platform's [TransportConnections] actual (JDBC on desktop, the system
 * sqlite3 on Apple): the `ATTACH`, the busy timeout pragma, the transaction and the `VACUUM INTO`
 * copy. The export rules themselves are covered in depth by desktopTest's `DeltaExporterTest`.
 *
 * The "live" database here is a stand-in with the synced tables, `sync_outbox`,
 * `ks_feed_folder_raw` (a feed's folder is exported from its raw assignment) and `ks_v2_edge` only.
 */
class DeltaExporterPlatformTest {
    private val gen = "0f1e2d3c-4b5a-4968-8778-695a4b3c2d1e"
    private val dev = "a1b2c3d4-e5f6-4789-8abc-def012345678"
    private val paths = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        paths.forEach { FileIO.delete(it); FileIO.delete("$it-journal") }
    }

    private fun newPath(prefix: String) = tempFilePath(prefix, ".db").also { paths += it }

    private fun liveDb(vararg rows: String): String {
        val path = newPath("ks-live-")
        TransportConnections.open(path, TransportOpenMode.CREATE).use { c ->
            TransportSchema.ddl.forEach(c::exec)
            c.exec("CREATE TABLE sync_outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT, tbl TEXT NOT NULL, pk TEXT NOT NULL, pk2 TEXT, heavy INTEGER NOT NULL DEFAULT 0)")
            c.exec("CREATE TABLE ks_feed_folder_raw (feed_id TEXT NOT NULL PRIMARY KEY, folder_updated_at INTEGER, folder_id TEXT)")
            c.exec("CREATE TABLE ks_v2_edge (kind TEXT NOT NULL, v2_id TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY (kind, v2_id, id))")
            rows.forEach(c::exec)
        }
        return path
    }

    private val rows = arrayOf(
        "INSERT INTO feeds (id, url, title, updated_at, created_at, sort_order) VALUES ('f1', 'https://f', 'F', 1, 1, 0)",
        "INSERT INTO articles (id, feed_id, guid, url, title, content, is_read, is_starred, cached_at, updated_at, created_at, body_updated_at) " +
            "VALUES ('a1', 'f1', 'g', 'https://a', 'A', 'body', 1, 0, 1, 1, 1, 1)",
        "INSERT INTO sync_outbox (tbl, pk, heavy) VALUES ('articles', 'a1', 0)",
    )

    private fun meta(kind: TransportFileKind) = TransportMeta(gen, kind, dev, 1, 1_000)

    private fun query(path: String, sql: String) =
        TransportConnections.open(path, TransportOpenMode.READ_ONLY).use { it.queryRows(sql) }

    @Test
    fun exportsALightDeltaWithItsClosure() {
        val live = liveDb(*rows)
        val dest = newPath("ks-delta-")
        val export = assertNotNull(DeltaExporter.exportDelta(live, dest, meta(TransportFileKind.DELTA)))
        assertEquals(1L, export.highWaterSeq)
        assertEquals(1L, export.rowCounts["feeds"])
        val name = TransportFileName(gen, TransportFileKind.DELTA, dev, 1, 1_000, "0123456789ab")
        assertEquals(TransportValidation.Valid, TransportFiles.validate(dest, ValidationTarget.Transport(name)))
        assertEquals(listOf(listOf("a1", null, "1")), query(dest, "SELECT id, content, is_read FROM articles"))
    }

    @Test
    fun anEmptyOutboxLeavesNoFile() {
        val live = liveDb(*rows.copyOfRange(0, 2))
        val dest = newPath("ks-delta-")
        assertNull(DeltaExporter.exportDelta(live, dest, meta(TransportFileKind.DELTA)))
        assertFalse(FileIO.exists(dest))
    }

    @Test
    fun aFullSendFromACopyCarriesBodies() {
        val live = liveDb(*rows)
        val copy = newPath("ks-copy-")
        DeltaExporter.copyForFullSend(live, copy)
        val dest = newPath("ks-full-")
        val export = DeltaExporter.exportFull(copy, dest, meta(TransportFileKind.BASE), exceptPath = null)
        assertEquals(1L, export.highWaterSeq)
        val name = TransportFileName(gen, TransportFileKind.BASE, dev, 1, 1_000, "0123456789ab")
        assertEquals(TransportValidation.Valid, TransportFiles.validate(dest, ValidationTarget.Transport(name)))
        assertEquals(listOf(listOf("a1", "body")), query(dest, "SELECT id, content FROM articles"))
    }
}
