package works.merc.keryx.app.data.local

/**
 * Thrown when `keryx.db`'s `PRAGMA user_version` is newer than the schema this build knows.
 *
 * The database was migrated by a newer app build (e.g. the SwiftUI app and the internal Compose
 * macOS build sharing one data directory, or an older release reinstalled over a newer one).
 * Opening it anyway would let this build write rows that lack the newer schema's columns, so the
 * driver factory refuses instead. This is an unexpected-state failure, not a `Result` error — see
 * `docs/error-design.md`.
 */
class DatabaseTooNewException(
    val databaseVersion: Long,
    val supportedVersion: Long,
) : IllegalStateException(
    "keryx.db schema version $databaseVersion is newer than this build supports ($supportedVersion)",
)

/**
 * Throws [DatabaseTooNewException] when [databaseVersion] is newer than [supportedVersion].
 * A lower version (including 0, a fresh file) is left to the caller's create/migrate path.
 */
fun requireSupportedSchemaVersion(databaseVersion: Long, supportedVersion: Long) {
    if (databaseVersion > supportedVersion) throw DatabaseTooNewException(databaseVersion, supportedVersion)
}

/** The [DatabaseTooNewException] in [throwable]'s cause chain (DI containers wrap it), if any. */
fun findDatabaseTooNew(throwable: Throwable): DatabaseTooNewException? =
    generateSequence(throwable) { it.cause }.filterIsInstance<DatabaseTooNewException>().firstOrNull()
