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
     * @param mainRuledOut whether the local `main` DB is ruled out as the cause of the failure.
     * Consulted **only** for [SqliteFailureCategory.CONSTRAINT]. `true` when `main` was not involved
     * at all — the failure came from the cloud-only phase before the merge transaction (migrating the
     * downloaded cloud file), which never touches `main` — or when [mainRuledOut] (the other
     * overload) pins the failure on the cloud's rows. `false` (the failure came from a FOREIGN KEY
     * violation `main` already held) and `null` (undetermined, or no verdict was recorded) both
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
        mainRuledOut: Boolean?,
        validateCloudSchema: () -> Boolean?,
        cloudIntegrityOk: () -> Boolean?,
    ): CloudDataIncompatibleException? = when (category) {
        // MergeSql's NOT EXISTS/EXISTS guards already rule out every collision with main's own
        // rows, and UNIQUE/NOT NULL are enforced on every local write, so once main's own FOREIGN
        // KEY violations are ruled out as the cause (the only constraint main itself can already
        // break), the only way a merge statement can still hit a constraint is the cloud DB's own
        // row set violating it (e.g. a duplicate url inside the cloud DB, a NULL where the cloud's
        // schema allowed one, or a cloud row whose parent exists nowhere) — data this app's schema
        // cannot represent, i.e. exactly what CloudDataIncompatibleException means.
        SqliteFailureCategory.CONSTRAINT -> when (mainRuledOut) {
            true -> CloudDataIncompatibleException("Cloud DB unusable ($errorCodeName)")
            // Main's own violation (false) or no trustworthy verdict (null): the failure may be
            // main's own, so it must not drive a reset of the cloud data.
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

    /**
     * Whether the local `main` DB is ruled out as the cause of a constraint failure of the merge
     * statements — the evidence [classify]'s `mainRuledOut` needs for a failure inside the merge
     * transaction. The actual gathers it on the merge's own connection while the failed merge
     * transaction (and its write lock on `main`) is still open, so no other writer can change `main`
     * in between; only the decision lives here, so every platform applies the same policy.
     *
     * **Shortcut for a failure known not to be a FOREIGN KEY violation.** When the
     * `isForeignKeyFailure` parameter is `false` (the driver's extended result code says UNIQUE /
     * PRIMARY KEY / NOT NULL), `main` is ruled out at once: [readViolationsBefore] and
     * [rerunWithDeferredForeignKeys] are never invoked. The verdict is the one the full path below
     * would reach anyway — only the cost differs. `main`'s existing state cannot cause such a
     * violation (those constraints are enforced on every write, and `MergeSql`'s guards rule out
     * collisions with `main`'s own rows), and re-running the same statement with FOREIGN KEY checks
     * deferred fails on the same violation ([DeferredRerunOutcome.ConstraintFailed] → `true`). Skipping
     * the re-run keeps the failed merge from holding its write lock on `main` for the re-run's whole
     * duration, which would push other writers toward `busy_timeout` for no information.
     *
     * This rests on `main` only ever already violating FOREIGN KEY constraints. Adding CHECK
     * constraints (there are none today) or triggers to the schema can break that assumption — a
     * CHECK added by a migration need not hold for existing rows, and a trigger can fail a statement
     * for reasons of `main`'s state — so such a change must revisit both this shortcut and the re-run
     * verdict below ([DeferredRerunOutcome.ConstraintFailed] → `true`). The triggers there are
     * (`sync_bookkeeping.sq`) keep it: they append to `sync_outbox`, create a feed's
     * `ks_feed_folder_raw` row, or write `feeds.folder_id` with a folder that exists and is alive, or
     * NULL — none of which can fail on `main`'s state.
     *
     * Every merge statement runs with immediate FOREIGN KEY checking, so the violations
     * `PRAGMA main.foreign_key_check` reports right after the failure ([readViolationsBefore]) are ones
     * `main` already held — data from before foreign keys were enforced, or a migration. With none,
     * `main` cannot be the cause and the failure is the cloud rows'. Otherwise the failure may only
     * have exposed one of them (a merge statement touching an orphaned row), so
     * [rerunWithDeferredForeignKeys] runs the failed statement and every one after it again, in the
     * same transaction, with FOREIGN KEY checks deferred, where only a non-FOREIGN KEY constraint can
     * fail:
     * - [DeferredRerunOutcome.ConstraintFailed]: a UNIQUE / NOT NULL / … violation by the cloud rows
     *   → `true`.
     * - [DeferredRerunOutcome.Completed] with a violation not in V0 (a set
     *   difference, not a count: the re-run may also resolve an old violation while adding a new
     *   one) → the merge brought in a FOREIGN KEY violation of the cloud rows' own → `true`.
     * - [DeferredRerunOutcome.Completed] with nothing new → the original failure came from touching
     *   a violation `main` already held → `false`.
     * - [DeferredRerunOutcome.Undetermined] → `null`.
     *
     * **Why the re-run continues from the failed statement.** It reaches the same state, and so the
     * same verdict, as going back to the start of the merge and re-running every statement with
     * FOREIGN KEY checks deferred: the statements before the failed one passed immediate FOREIGN KEY
     * checking, which deferring would not have changed, and the failed statement's own changes were
     * undone with its failure (a FOREIGN KEY failure always aborts the whole statement; only a
     * non-FOREIGN KEY failure under an explicit `OR FAIL` keeps part of its changes, and re-running
     * such a statement fails on a constraint again — the verdict that failure deserves anyway; neither
     * `MergeSql` nor the schema uses `OR FAIL`). Both this and reading V0 as "what main already held"
     * also rest on no single statement bringing in a FOREIGN KEY violation while resolving another one:
     * SQLite checks an immediate constraint by the statement's net count of violations, so such a
     * statement would succeed and leave its new violation in V0. `MergeSql`'s statements bring in none
     * (every foreign-key value they write is guarded to exist in main, or NULL); a statement added later
     * must keep that property. Going back is not an option on every platform anyway:
     * Android's requery cannot run a `ROLLBACK TO` — its `SQLiteSession` takes any statement starting
     * with `ROLLBACK` (after `trim()` and comments) for a rollback of its own whole transaction.
     *
     * The actual must run the re-run only while the failed merge transaction is still open — each
     * proves that its own way before running anything — so no statement can run outside it and commit
     * into `main`; and it rolls the whole transaction back afterwards, never committing.
     *
     * @param isForeignKeyFailure whether the failure is a FOREIGN KEY violation, from the driver's
     * extended result code (mapped by [MergeFailureClassifier.isForeignKeyFailure]): `true` for
     * `SQLITE_CONSTRAINT_FOREIGNKEY`, `false` for UNIQUE / PRIMARY KEY / NOT NULL (the shortcut above),
     * `null` when the driver exposes no extended code or it is any other. `true` and `null` both take
     * the full path.
     * @param readViolationsBefore reads `PRAGMA main.foreign_key_check`'s rows right after the
     * failure (V0), returning `null` when they could not be read inside the failed merge transaction
     * (it had already ended); a failing check may throw instead. Not invoked when
     * [isForeignKeyFailure] is `false`.
     * @param rerunWithDeferredForeignKeys runs the failed statement and every one after it (see
     * [remainingStatements]) with FOREIGN KEY checks deferred; invoked only when [isForeignKeyFailure]
     * is not `false` and V0 is non-empty.
     * @return `true` if `main` is ruled out, `false` if `main` is the cause, `null` if undetermined.
     */
    fun mainRuledOut(
        isForeignKeyFailure: Boolean?,
        readViolationsBefore: () -> Set<ForeignKeyViolation>?,
        rerunWithDeferredForeignKeys: () -> DeferredRerunOutcome,
    ): Boolean? {
        if (isForeignKeyFailure == false) return true
        val violationsBefore = readViolationsBefore() ?: return null
        if (violationsBefore.isEmpty()) return true
        return when (val outcome = rerunWithDeferredForeignKeys()) {
            DeferredRerunOutcome.ConstraintFailed -> true
            is DeferredRerunOutcome.Completed -> (outcome.violationsAfter - violationsBefore).isNotEmpty()
            DeferredRerunOutcome.Undetermined -> null
        }
    }

    /**
     * Whether a constraint failure with SQLite *extended* result code [extendedResultCode] is a
     * FOREIGN KEY violation — the observation [mainRuledOut]'s `isForeignKeyFailure` takes, mapped here
     * so every platform whose driver exposes the extended code reads it the same way.
     *
     * @return `true` for `SQLITE_CONSTRAINT_FOREIGNKEY`; `false` for `SQLITE_CONSTRAINT_UNIQUE`,
     * `SQLITE_CONSTRAINT_PRIMARYKEY` and `SQLITE_CONSTRAINT_NOTNULL`, which `main`'s existing state
     * cannot cause; `null` for any other code — the bare primary `SQLITE_CONSTRAINT` (all a driver
     * without extended codes reports), `SQLITE_CONSTRAINT_CHECK`, `SQLITE_CONSTRAINT_TRIGGER`, … — so
     * an unrecognized code takes [mainRuledOut]'s full path rather than the shortcut.
     */
    fun isForeignKeyFailure(extendedResultCode: Int): Boolean? = when (extendedResultCode) {
        SQLITE_CONSTRAINT_FOREIGNKEY -> true
        SQLITE_CONSTRAINT_UNIQUE, SQLITE_CONSTRAINT_PRIMARYKEY, SQLITE_CONSTRAINT_NOTNULL -> false
        else -> null
    }

    /**
     * The statements [mainRuledOut]'s re-run runs: the one at [failedIndex] in [mergeStatements] —
     * the statement whose failure is being classified — and every one after it.
     */
    fun remainingStatements(mergeStatements: List<String>, failedIndex: Int): List<String> =
        mergeStatements.drop(failedIndex)

    // SQLite extended result codes (sqlite3.h: SQLITE_CONSTRAINT | (n << 8)).
    private const val SQLITE_CONSTRAINT_FOREIGNKEY = 787
    private const val SQLITE_CONSTRAINT_NOTNULL = 1299
    private const val SQLITE_CONSTRAINT_PRIMARYKEY = 1555
    private const val SQLITE_CONSTRAINT_UNIQUE = 2067
}

/**
 * One row of `PRAGMA foreign_key_check`: a row of [table] (by [rowId], `null` for a `WITHOUT ROWID`
 * table) whose foreign key number [foreignKeyId] points at a row missing from [parent]. Compared as a
 * whole, so the same violation read twice is equal.
 */
data class ForeignKeyViolation(
    val table: String,
    val rowId: Long?,
    val parent: String,
    val foreignKeyId: Long,
)

/**
 * What re-running every merge statement with FOREIGN KEY checks deferred showed — see
 * [MergeFailureClassifier.mainRuledOut].
 */
sealed interface DeferredRerunOutcome {
    /**
     * A statement failed with a constraint error. With foreign keys deferred, that is a
     * non-FOREIGN KEY constraint (UNIQUE / NOT NULL / PRIMARY KEY / CHECK).
     */
    data object ConstraintFailed : DeferredRerunOutcome

    /**
     * Every statement ran; [violationsAfter] is `PRAGMA main.foreign_key_check`'s rows afterwards,
     * read inside the same merge transaction.
     */
    data class Completed(val violationsAfter: Set<ForeignKeyViolation>) : DeferredRerunOutcome

    /**
     * Any other outcome: a statement failed with a non-constraint error, the merge transaction was no
     * longer open, or the check after the re-run failed.
     */
    data object Undetermined : DeferredRerunOutcome
}

/**
 * Carries the "main is not the cause" verdict ([MergeFailureClassifier.classify]'s `mainRuledOut`)
 * from where a `DatabaseMerger` actual can establish it — the catch block around its cloud-only phase
 * (always `true`: main is not involved), or the one around its merge statements
 * ([MergeFailureClassifier.mainRuledOut], whose evidence can only be gathered race-free there, inside
 * the failed transaction) — to the classification step, without wrapping or replacing the original
 * failure.
 *
 * The verdict is tied to the failure it was computed for: [mainRuledOutFor] answers `null` for any
 * other throwable (e.g. one a later cleanup step threw instead), so a verdict can never be applied to
 * a failure it says nothing about. One instance per merge call.
 */
internal class MainRuledOutVerdict {
    private var failure: Throwable? = null
    private var mainRuledOut: Boolean? = null

    /** Records [mainRuledOut] as the verdict computed for [failure]. */
    fun record(failure: Throwable, mainRuledOut: Boolean?) {
        this.failure = failure
        this.mainRuledOut = mainRuledOut
    }

    /** The verdict recorded for exactly [failure], or `null` if none was. */
    fun mainRuledOutFor(failure: Throwable): Boolean? = if (failure === this.failure) mainRuledOut else null
}
