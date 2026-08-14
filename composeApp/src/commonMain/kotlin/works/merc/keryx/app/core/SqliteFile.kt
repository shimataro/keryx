package works.merc.keryx.app.core

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** The 16-byte magic string every valid SQLite database file starts with. */
private val SQLITE_FILE_HEADER = byteArrayOf(
    0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66,
    0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00,
)

/**
 * Cheap O(1) check that [bytes] begins with SQLite's file magic. Not an integrity check — it only
 * rejects payloads that are definitely not a SQLite database (truncated, empty, an HTML error
 * page, an encrypted/foreign blob) before they are written to disk and opened.
 */
fun looksLikeSqliteFile(bytes: ByteArray?): Boolean =
    bytes != null &&
        bytes.size >= SQLITE_FILE_HEADER.size &&
        bytes.copyOf(SQLITE_FILE_HEADER.size).contentEquals(SQLITE_FILE_HEADER)

/**
 * The same check against a file on disk, reading only its first bytes.
 *
 * The sync DB is streamed to disk rather than held in memory, so this is the form the sync flow
 * actually uses: it inspects the 16-byte header without loading a payload whose whole point was
 * never to be loaded. A missing or too-short file is rejected, like an unusable byte array.
 */
fun looksLikeSqliteFile(path: String): Boolean {
    val header = ByteArray(SQLITE_FILE_HEADER.size)
    val read = try {
        SystemFileSystem.source(Path(path)).buffered().use { source ->
            source.readAtMostTo(header, 0, header.size)
        }
    } catch (_: Exception) {
        return false // absent or unreadable — not something we can merge either way
    }
    return read == header.size && header.contentEquals(SQLITE_FILE_HEADER)
}
