package works.merc.keryx.app.platform

import works.merc.keryx.app.domain.TransportFileKind
import works.merc.keryx.app.domain.TransportFileName
import works.merc.keryx.app.domain.TransportMeta
import works.merc.keryx.app.tempFilePath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [TransportFiles] through each platform's [TransportConnections] actual (JDBC on desktop, the
 * system sqlite3 on Apple): create, validate read-only, fold. The checks themselves are covered in
 * depth by desktopTest's `TransportFilesTest`.
 */
class TransportFilesPlatformTest {
    private val gen = "0f1e2d3c-4b5a-4968-8778-695a4b3c2d1e"
    private val dev = "a1b2c3d4-e5f6-4789-8abc-def012345678"
    private val paths = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        paths.forEach { FileIO.delete(it); FileIO.delete("$it-journal") }
    }

    private fun newFile(name: TransportFileName, vararg rows: String): String {
        val path = tempFilePath("ks-", ".db").also { paths += it }
        TransportFiles.createEmpty(path, TransportMeta(name.generation, name.kind, name.device, name.number, 1))
        TransportConnections.open(path, TransportOpenMode.READ_WRITE).use { c -> rows.forEach(c::exec) }
        return path
    }

    private val feed =
        "INSERT INTO feeds (id, url, title, updated_at, created_at, sort_order) VALUES ('f1', 'https://f', 'F', 1, 1, 0)"

    @Test
    fun createValidateAndFold() {
        val d1 = TransportFileName(gen, TransportFileKind.DELTA, dev, 1, "0123456789ab")
        val d2 = TransportFileName(gen, TransportFileKind.DELTA, dev, 2, "0123456789ab")
        val first = newFile(d1, feed)
        val second = newFile(
            d2,
            feed,
            "INSERT INTO articles (id, feed_id, guid, url, title, is_read, is_starred, cached_at, updated_at, created_at) " +
                "VALUES ('a1', 'f1', 'g', 'https://a', 'A', 0, 0, 1, 1, 1)",
        )
        assertEquals(TransportValidation.Valid, TransportFiles.validate(first, ValidationTarget.Transport(d1)))
        assertEquals(TransportValidation.Valid, TransportFiles.validate(second, ValidationTarget.Transport(d2)))

        val baseName = TransportFileName(gen, TransportFileKind.BASE, dev, 1, "0123456789ab")
        val acc = newFile(baseName)
        TransportFiles.fold(acc, first, d1)
        TransportFiles.fold(acc, second, d2)
        assertEquals(TransportValidation.Valid, TransportFiles.validate(acc, ValidationTarget.Transport(baseName)))
        TransportConnections.open(acc, TransportOpenMode.READ_ONLY).use { c ->
            assertEquals(listOf(listOf(dev, "2")), c.queryRows("SELECT device, seq FROM ks_vector"))
            assertEquals(listOf(d1.fileName, d2.fileName).sorted(), c.queryRows("SELECT name FROM ks_covered ORDER BY name").map { it[0] })
            assertEquals(listOf(listOf("a1", null)), c.queryRows("SELECT id, content FROM articles"))
        }
    }

    @Test
    fun aViolationIsCorruptAndAMissingFileUndetermined() {
        val d1 = TransportFileName(gen, TransportFileKind.DELTA, dev, 1, "0123456789ab")
        val file = newFile(d1, feed, "UPDATE feeds SET updated_at = 'soon'")
        val result = TransportFiles.validate(file, ValidationTarget.Transport(d1))
        assertIs<TransportValidation.Corrupt>(result)
        assertTrue("feeds.updated_at" in result.reason, result.reason)

        val missing = tempFilePath("ks-missing-", ".db")
        assertIs<TransportValidation.Undetermined>(TransportFiles.validate(missing, ValidationTarget.Transport(d1)))
        assertTrue(!FileIO.exists(missing))
    }
}
