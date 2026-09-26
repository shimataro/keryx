package works.merc.keryx.app.domain

import works.merc.keryx.app.core.CloudDataIncompatibleException

/**
 * The platform-independent categories a SQLite merge failure is reduced to before it is
 * classified by [MergeFailureClassifier].
 *
 * Each platform's `DatabaseMerger` actual maps its own driver's error code onto one of these — the
 * JVM reads `org.sqlite.SQLiteException.resultCode`, other targets whatever their driver exposes —
 * so the classification policy itself stays free of any driver type.
 */
enum class SqliteFailureCategory {
    /**
     * Not a database / corrupt / bad format / empty-but-expected-populated, or a
     * UNIQUE/NOT NULL/FOREIGN KEY violation (`SQLITE_NOTADB`, `SQLITE_CORRUPT`, `SQLITE_FORMAT`,
     * `SQLITE_EMPTY`, `SQLITE_CONSTRAINT`).
     */
    CORRUPT_OR_CONSTRAINT,

    /**
     * A statement-level error — `no such table` / `no such column` (`SQLITE_ERROR`). Ambiguous:
     * it is what a foreign/legacy cloud schema looks like, but also what a broken *local* schema
     * looks like.
     */
    STATEMENT_ERROR,

    /**
     * Any other error code (`SQLITE_CANTOPEN`, `SQLITE_IOERR`, `SQLITE_BUSY`, …), or a failure with
     * no SQLite error behind it at all.
     */
    OTHER,
}

/**
 * Decides whether a merge failure means the cloud DB is permanently unusable
 * ([CloudDataIncompatibleException]) or is transient / an app bug, from the failure's
 * [SqliteFailureCategory] — never from message text, which is locale- and driver-version-fragile.
 *
 * Deliberately conservative: a category this cannot pin on the cloud yields `null`, so a miss
 * never regresses behavior — the caller rethrows the original failure and `SyncRepository`'s own
 * catch-all already reports it as a transient
 * [works.merc.keryx.app.core.CloudStorageException].
 */
object MergeFailureClassifier {

    /**
     * @param category the failure reduced to a driver-independent category.
     * @param errorCodeName the driver's own name for the error code, for the exception message.
     * @param validateCloudSchema validates the downloaded cloud file against the app's expected
     * schema (`DatabaseMerger.validateSchema`). Called **only** for [SqliteFailureCategory.STATEMENT_ERROR],
     * since it reopens the file; `null` means undetermined and is treated exactly like `true`.
     * @return the exception to throw instead of the original failure, or `null` to leave the
     * original failure unchanged.
     */
    fun classify(
        category: SqliteFailureCategory,
        errorCodeName: String,
        validateCloudSchema: () -> Boolean?,
    ): CloudDataIncompatibleException? = when (category) {
        // MergeSql's NOT EXISTS/EXISTS guards already rule out every collision with main's own
        // rows, so the only way a merge statement can still hit a constraint is the cloud DB's own
        // row set violating it (e.g. a duplicate url inside the cloud DB, or a NULL where the
        // cloud's schema allowed one) — data this app's schema cannot represent, i.e. exactly what
        // CloudDataIncompatibleException means.
        SqliteFailureCategory.CORRUPT_OR_CONSTRAINT ->
            CloudDataIncompatibleException("Cloud DB unusable ($errorCodeName)")
        // Ambiguous, so disambiguate against the downloaded cloud file itself.
        SqliteFailureCategory.STATEMENT_ERROR -> when (validateCloudSchema()) {
            false -> CloudDataIncompatibleException("Cloud DB schema is incompatible")
            // true (cloud schema looks fine) or null (undetermined) both mean this cannot be
            // confidently pinned on the cloud — leave it as a transient/app-bug failure.
            true, null -> null
        }
        SqliteFailureCategory.OTHER -> null
    }
}
