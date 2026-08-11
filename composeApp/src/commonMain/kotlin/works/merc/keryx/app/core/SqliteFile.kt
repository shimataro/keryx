package works.merc.keryx.app.core

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
