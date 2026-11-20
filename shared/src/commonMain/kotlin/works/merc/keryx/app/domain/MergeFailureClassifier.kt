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
     * A UNIQUE / NOT NULL / FOREIGN KEY violation (`SQLITE_CONSTRAINT` and every extended
     * `SQLITE_CONSTRAINT_*` code). Not on its own proof that the cloud is at fault: a FOREIGN KEY
     * violation already present in the *local* `main` DB can surface during the merge too.
     */
    CONSTRAINT,

    /**
     * Not a database / corrupt / bad format / empty-but-expected-populated (`SQLITE_NOTADB`,
     * `SQLITE_CORRUPT`, `SQLITE_FORMAT`, `SQLITE_EMPTY`). Not on its own proof that the cloud is at
     * fault either: the corrupt page may be in the *local* `main` DB.
     */
    CORRUPT,

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
 * ([CloudDataIncompatibleException]) or is transient / an app bug / a local-side fault, from the
 * failure's [SqliteFailureCategory] — never from message text, which is locale- and
 * driver-version-fragile — plus evidence that pins the failure on the cloud rather than on the
 * local `main` DB.
 *
 * Deliberately conservative: a failure this cannot pin on the cloud yields `null`, so a miss never
 * offers the destructive cloud-data reset — the caller rethrows the original failure and
 * `SyncRepository`'s own catch-all reports it as a transient
 * [works.merc.keryx.app.core.CloudStorageException].
 */
object MergeFailureClassifier {

    /**
     * @param category the failure reduced to a driver-independent category.
     * @param errorCodeName the driver's own name for the error code, for the exception message.
     * @param localForeignKeysSound whether the local `main` DB is ruled out as the cause of the
     * failure. Consulted **only** for [SqliteFailureCategory.CONSTRAINT]. `true` when `main` was not
     * involved at all — the failure came from the cloud-only phase before the merge transaction
     * (migrating the downloaded cloud file), which never touches `main` — or when `main`'s FOREIGN
     * KEY check is clean. The actual computes the latter with `PRAGMA main.foreign_key_check` on the
     * merge's own connection, inside the failed merge's still-open write transaction and before its
     * rollback, so no other writer can change `main` between the failure and the check. UNIQUE /
     * NOT NULL are always enforced on every local write and the merge statements before the failing
     * one passed immediate FOREIGN KEY checking, so a violation found there pre-existed in `main`:
     * the constraint failure may be local, not the cloud's. `false` (a violation found) and `null`
     * (the check could not run in that transaction, or failed, or no verdict was recorded) both
     * leave the failure unclassified.
     * @param validateCloudSchema validates the downloaded cloud file against the app's expected
     * schema (`DatabaseMerger.validateSchema`). Called **only** for [SqliteFailureCategory.STATEMENT_ERROR],
     * since it reopens the file; `null` means undetermined and is treated exactly like `true`.
     * @param cloudIntegrityOk runs `PRAGMA quick_check` against the downloaded cloud file, after the
     * merge's rollback. Called **only** for [SqliteFailureCategory.CORRUPT]. `false` (problems
     * reported, or opening/checking the file itself failed as not-a-database/corrupt) pins the
     * corruption on the cloud; `true` (the cloud file is intact, so the corruption is local) and
     * `null` (undetermined) leave the failure unclassified. Nothing else writes the downloaded
     * temp file, so this check cannot race with another writer.
     * @return the exception to throw instead of the original failure, or `null` to leave the
     * original failure unchanged.
     */
    fun classify(
        category: SqliteFailureCategory,
        errorCodeName: String,
        localForeignKeysSound: Boolean?,
        validateCloudSchema: () -> Boolean?,
        cloudIntegrityOk: () -> Boolean?,
    ): CloudDataIncompatibleException? = when (category) {
        // MergeSql's NOT EXISTS/EXISTS guards already rule out every collision with main's own
        // rows, so once main is known to be free of FOREIGN KEY violations (the only constraint
        // main itself can already break — UNIQUE/NOT NULL are enforced on every local write), the
        // only way a merge statement can still hit a constraint is the cloud DB's own row set
        // violating it (e.g. a duplicate url inside the cloud DB, or a NULL where the cloud's
        // schema allowed one) — data this app's schema cannot represent, i.e. exactly what
        // CloudDataIncompatibleException means.
        SqliteFailureCategory.CONSTRAINT -> when (localForeignKeysSound) {
            true -> CloudDataIncompatibleException("Cloud DB unusable ($errorCodeName)")
            // A pre-existing local violation (false) or no trustworthy verdict (null): the
            // failure may be main's own, so it must not drive a reset of the cloud data.
            false, null -> null
        }
        // Either file may hold the corrupt page; only the cloud file's own integrity check can
        // pin it on the cloud.
        SqliteFailureCategory.CORRUPT -> when (cloudIntegrityOk()) {
            false -> CloudDataIncompatibleException("Cloud DB unusable ($errorCodeName)")
            // true: the cloud file is intact, so the corruption is local. null: undetermined.
            true, null -> null
        }
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

/**
 * Carries the "main is not the cause" verdict ([MergeFailureClassifier.classify]'s
 * `localForeignKeysSound`) from where a `DatabaseMerger` actual can establish it — the catch block
 * around its cloud-only phase (always `true`: main is not involved), or the one around its merge
 * statements (the local FOREIGN KEY check, which can only be computed race-free there, inside the
 * failed transaction) — to the classification step, without wrapping or replacing the original
 * failure.
 *
 * The verdict is tied to the failure it was computed for: [soundFor] answers `null` for any other
 * throwable (e.g. one a later cleanup step threw instead), so a verdict can never be applied to a
 * failure it says nothing about. One instance per merge call.
 */
internal class LocalForeignKeyVerdict {
    private var failure: Throwable? = null
    private var sound: Boolean? = null

    /** Records [sound] as the verdict computed for [failure]. */
    fun record(failure: Throwable, sound: Boolean?) {
        this.failure = failure
        this.sound = sound
    }

    /** The verdict recorded for exactly [failure], or `null` if none was. */
    fun soundFor(failure: Throwable): Boolean? = if (failure === this.failure) sound else null
}
