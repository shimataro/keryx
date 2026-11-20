package works.merc.keryx.app.platform

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.requery.android.database.sqlite.SQLiteDatabase
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.DeferredRerunOutcome
import works.merc.keryx.app.domain.ForeignKeyViolation
import works.merc.keryx.app.domain.MainRuledOutVerdict
import works.merc.keryx.app.domain.MergeFailureClassifier
import works.merc.keryx.app.domain.MergeSchema
import works.merc.keryx.app.domain.SqliteFailureCategory

/**
 * Android implementation. Opens a dedicated `io.requery.android.database.sqlite.SQLiteDatabase`
 * connection directly — the Android equivalent of the desktop actual's dedicated JDBC connection —
 * rather than going through `data/local/DatabaseDriverFactory`'s `AndroidSqliteDriver`. Unlike
 * desktop's `JdbcSqliteDriver` (which opens a fresh connection per statement, forcing the whole
 * attach/merge/detach sequence onto one explicit connection), `AndroidSqliteDriver` holds a single
 * long-lived connection — but [works.merc.keryx.app.data.local.DatabaseDriverFactory]'s connection
 * is still not reused here, both to keep this class's SQLite-driver knowledge independent of the
 * app's live driver (per `.claude/rules/sync-merge.md`) and because [merge] must be free to run
 * against the local DB without going through SQLDelight's own query-listener bookkeeping mid-merge.
 *
 * [NoOpDatabaseErrorHandler] is required on every connection opened here — see its own KDoc.
 */
actual object DatabaseMerger {
    /**
     * Merges cloud database changes into a local SQLite database.
     *
     * @param localDbPath The path to the local database.
     * @param cloudDbPath The path to the cloud database.
     * @param localSchemaVersion The schema version supported by the local database.
     * @param mergeStatements SQL statements to apply during the merge.
     * @throws SchemaVersionException If the cloud database schema is newer than the local schema.
     * @throws CloudDataIncompatibleException If the cloud database is corrupt, or its data
     * violates this app's schema constraints (classified from the thrown exception's class plus
     * evidence pinning it on the cloud — see [classifyMergeFailure]). Any other failure is rethrown
     * unchanged (transient / an app bug / a local-side fault).
     */
    actual fun merge(
        localDbPath: String,
        cloudDbPath: String,
        localSchemaVersion: Long,
        mergeStatements: List<String>,
    ) {
        val verdict = MainRuledOutVerdict()
        try {
            mergeUnclassified(localDbPath, cloudDbPath, localSchemaVersion, mergeStatements, verdict)
        } catch (e: SchemaVersionException) {
            // Already classified — must not fall into the Throwable catch-all below, which would
            // reclassify it as CloudDataIncompatibleException (the wrong recovery action: this
            // needs an app update, not a cloud-data reset).
            throw e
        } catch (e: Throwable) {
            throw classifyMergeFailure(e, cloudDbPath, localSchemaVersion, verdict.mainRuledOutFor(e))
        }
    }

    /**
     * @param verdict receives the "main is not the cause" verdict: `true` for any failure of the
     * cloud-only phase ([migrateCloudIfOlder], which never touches main), and for a constraint failure
     * of the merge statements the verdict computed inside the failed transaction before its rollback
     * (see [mainRuledOut]).
     */
    private fun mergeUnclassified(
        localDbPath: String,
        cloudDbPath: String,
        localSchemaVersion: Long,
        mergeStatements: List<String>,
        verdict: MainRuledOutVerdict,
    ) {
        // Bring an older cloud DB up to the local schema first, so the merge statements can
        // reference columns added in newer versions without a "no such column" failure.
        try {
            migrateCloudIfOlder(cloudDbPath, localSchemaVersion)
        } catch (e: Throwable) {
            // This phase touches only the downloaded cloud temp file, never main, so main cannot be
            // the cause of anything it throws (e.g. a constraint failure while migrating the cloud
            // file): record that verdict for the classification step.
            verdict.record(e, mainRuledOut = true)
            throw e
        }

        SQLiteDatabase.openOrCreateDatabase(localDbPath, null, NoOpDatabaseErrorHandler).use { db ->
            db.execSQL("PRAGMA foreign_keys=ON")
            db.setBusyTimeout()
            // ATTACH must run outside a transaction.
            db.execSQL("ATTACH DATABASE '${cloudDbPath.replace("'", "''")}' AS cloud")
            try {
                val cloudVersion = db.rawQuery("PRAGMA cloud.user_version", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                }
                if (cloudVersion > localSchemaVersion) {
                    throw SchemaVersionException(localVersion = localSchemaVersion, cloudVersion = cloudVersion)
                }

                db.beginTransaction()
                var failure: Throwable? = null
                // The merge statement running (or that ran last); see mainRuledOut.
                var statementIndex = 0
                try {
                    // Marks this transaction for mainRuledOut: a savepoint lives only as long as the
                    // transaction it was made in, so it still being there after the checks proves
                    // they ran inside this transaction. Committed (or rolled back) together with the
                    // transaction by endTransaction() below.
                    db.execSQL("SAVEPOINT $MERGE_SAVEPOINT")
                    for ((index, sql) in mergeStatements.withIndex()) {
                        statementIndex = index
                        db.execSQL(sql)
                    }
                    db.setTransactionSuccessful()
                } catch (e: Throwable) {
                    // Must run before endTransaction() rolls back: only while the failed
                    // transaction (and its write lock on main) is still held can no other writer
                    // change main between the failure and the check.
                    if (e.failureCategory() == SqliteFailureCategory.CONSTRAINT) {
                        verdict.record(e, db.mainRuledOut(MergeFailureClassifier.remainingStatements(mergeStatements, statementIndex), e))
                    }
                    failure = e
                    throw e
                } finally {
                    // endTransaction() rolls back automatically when setTransactionSuccessful()
                    // was not reached (i.e. a merge statement threw) — the Android transaction
                    // API's equivalent of the desktop actual's explicit connection.rollback().
                    // Its own failure (e.g. once SQLite has already rolled the transaction back by
                    // itself) must not replace (and hide) the merge failure being classified.
                    runCatching { db.endTransaction() }.exceptionOrNull()?.let { endFailure ->
                        failure?.addSuppressed(endFailure) ?: throw endFailure
                    }
                }
            } finally {
                runCatching { db.execSQL("DETACH DATABASE cloud") }
            }
        }
    }

    /**
     * Classifies a merge failure as a permanently-unusable cloud DB
     * ([CloudDataIncompatibleException]) or leaves it unchanged (transient / an app bug).
     *
     * Unlike the desktop actual (which reduces `SQLiteException.resultCode`, a numeric error code
     * the JDBC driver exposes), Android's `SQLiteException` carries no such code — see
     * `app-architecture.md`'s `DatabaseMerger` section. Classification here goes by the thrown
     * exception's own subclass instead. The classification *policy* itself
     * ([MergeFailureClassifier]) is unchanged and still only sees the platform-independent
     * [SqliteFailureCategory].
     */
    private fun classifyMergeFailure(
        e: Throwable,
        cloudDbPath: String,
        localSchemaVersion: Long,
        mainRuledOut: Boolean?,
    ): Throwable {
        val category = e.failureCategory() ?: return e
        val classified = MergeFailureClassifier.classify(
            category = category,
            errorCodeName = e::class.simpleName ?: "SQLiteException",
            mainRuledOut = mainRuledOut,
            validateCloudSchema = { validateSchema(cloudDbPath, localSchemaVersion) },
            cloudIntegrityOk = { cloudIntegrityOk(cloudDbPath) },
        )
        if (classified == null) {
            if (category == SqliteFailureCategory.CONSTRAINT || category == SqliteFailureCategory.CORRUPT) {
                Log.warn(
                    TAG,
                    "Merge failure not pinned on the cloud DB (category=$category, exception=${e::class.simpleName}, " +
                        "mainRuledOut=$mainRuledOut): ${e.message}",
                )
            }
            return e
        }
        Log.warn(TAG, "${classified.message} (category=$category, exception=${e::class.simpleName}): ${e.message}")
        return classified
    }

    /**
     * Reduces this failure to a driver-independent [SqliteFailureCategory], or `null` if it is not
     * a SQLite failure at all. A subclass this app doesn't specifically recognize (e.g.
     * `SQLiteMisuseException`, `SQLiteTableLockedException`, `SQLiteOutOfMemoryException`,
     * `SQLiteFullException`'s less common siblings) falls into [OTHER] rather than being ignored,
     * matching the desktop actual's own catch-all `else` branch for an unrecognized SQLite result
     * code — the `else -> SqliteFailureCategory.OTHER` branch below is what does this; the
     * `STATEMENT_ERROR` branch above it matches only the *exact* `SQLiteException` class, not its
     * subclasses (Kotlin's `is` would otherwise match every subclass too, which previously routed
     * every unrecognized subclass into the ambiguous `STATEMENT_ERROR` bucket instead — the one
     * that can escalate to a destructive [CloudDataIncompatibleException] via `validateCloudSchema`
     * once cloud data merely happens to look schema-incompatible for an unrelated reason).
     *
     * `internal` rather than `private` so this subclass-classification logic can be unit-tested
     * directly against synthetic exception instances (see `MergeFailureClassificationDeviceTest`'s
     * `failureCategory` cases) — exercising every specific subclass through a real ATTACH/merge
     * would be far more fragile than constructing e.g. a bare `SQLiteTableLockedException` and
     * checking the category it maps to.
     */
    internal fun Throwable.failureCategory(): SqliteFailureCategory? = when {
        // Android exposes no extended code, so a FOREIGN KEY violation cannot be told apart from a
        // UNIQUE/NOT NULL one here — mainRuledOut tells them apart without the extended code.
        this is SQLiteConstraintException -> SqliteFailureCategory.CONSTRAINT
        // Covers SQLITE_CORRUPT and SQLITE_NOTADB alike (both surface as this class).
        this is SQLiteDatabaseCorruptException -> SqliteFailureCategory.CORRUPT
        // A plain SQLiteException (not one of the more specific subclasses in this file) is what
        // "no such table"/"no such column" looks like — ambiguous, so MergeFailureClassifier
        // disambiguates it against the downloaded cloud file itself (validateCloudSchema).
        this::class == SQLiteException::class -> SqliteFailureCategory.STATEMENT_ERROR
        this is SQLiteException -> SqliteFailureCategory.OTHER
        else -> null
    }

    /**
     * Whether the local `main` DB is ruled out as the cause of the constraint failure [failure] —
     * [MergeFailureClassifier.mainRuledOut]'s policy, fed with evidence gathered on this (the merge's)
     * connection while the failed merge transaction is still open.
     *
     * The re-run runs [remainingStatements] (the failed statement and every one after it — see
     * [MergeFailureClassifier.mainRuledOut] for why that gives the right verdict).
     *
     * `inTransaction()` only reflects requery's own transaction bookkeeping, not whether SQLite rolled
     * the transaction back by itself, so the checks are followed by releasing [MERGE_SAVEPOINT]: that
     * savepoint disappears with any rollback of the transaction, so releasing it successfully proves
     * every check read `main` inside the failed transaction. Only then does the verdict count;
     * endTransaction() then rolls the whole transaction back.
     *
     * @return `true` / `false` as [MergeFailureClassifier.mainRuledOut], or `null` when undetermined;
     * a failure of any check is added to [failure] as suppressed, never replacing it.
     */
    private fun SQLiteDatabase.mainRuledOut(remainingStatements: List<String>, failure: Throwable): Boolean? {
        val ruledOut = try {
            MergeFailureClassifier.mainRuledOut(
                // requery exposes no extended result code, so a FOREIGN KEY violation cannot be told
                // from a UNIQUE / NOT NULL one here: always the full path.
                isForeignKeyFailure = null,
                readViolationsBefore = { if (inTransaction()) foreignKeyViolations() else null },
            ) {
                rerunWithDeferredForeignKeys(remainingStatements, failure)
            }
        } catch (checkFailure: Throwable) {
            failure.addSuppressed(checkFailure)
            null
        }
        val stillOpen = try {
            execSQL("RELEASE $MERGE_SAVEPOINT")
            true
        } catch (releaseFailure: Throwable) {
            failure.addSuppressed(releaseFailure)
            false
        }
        return if (stillOpen) ruledOut else null
    }

    /**
     * Runs [remainingStatements] again with FOREIGN KEY checks deferred (`PRAGMA defer_foreign_keys`,
     * which SQLite switches off again when the transaction ends) — see
     * [MergeFailureClassifier.mainRuledOut]. Its own SQL failures other than a re-run statement's are
     * thrown to [mainRuledOut].
     *
     * First proves the merge transaction is still open, by releasing [MERGE_SAVEPOINT] and making it
     * again in the same transaction: had SQLite already ended the transaction, the release fails and
     * nothing runs, so no statement can run outside it and commit straight into main. Whether it
     * stayed open throughout is proven afterwards, by [mainRuledOut]'s release of the new savepoint.
     */
    private fun SQLiteDatabase.rerunWithDeferredForeignKeys(
        remainingStatements: List<String>,
        failure: Throwable,
    ): DeferredRerunOutcome {
        execSQL("RELEASE $MERGE_SAVEPOINT")
        execSQL("SAVEPOINT $MERGE_SAVEPOINT")
        execSQL("PRAGMA defer_foreign_keys=ON")
        try {
            for (sql in remainingStatements) execSQL(sql)
        } catch (rerunFailure: Throwable) {
            if (rerunFailure.failureCategory() == SqliteFailureCategory.CONSTRAINT) {
                return DeferredRerunOutcome.ConstraintFailed
            }
            failure.addSuppressed(rerunFailure)
            return DeferredRerunOutcome.Undetermined
        }
        return DeferredRerunOutcome.Completed(foreignKeyViolations())
    }

    /** `PRAGMA main.foreign_key_check`'s rows. */
    private fun SQLiteDatabase.foreignKeyViolations(): Set<ForeignKeyViolation> =
        rawQuery("PRAGMA main.foreign_key_check", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(
                        ForeignKeyViolation(
                            table = cursor.getString(0),
                            rowId = if (cursor.isNull(1)) null else cursor.getLong(1),
                            parent = cursor.getString(2),
                            foreignKeyId = cursor.getLong(3),
                        ),
                    )
                }
            }
        }

    /**
     * Runs `PRAGMA quick_check` against the downloaded cloud file — see
     * [MergeFailureClassifier.classify]'s `cloudIntegrityOk`. Non-creating (same reason as
     * [validateSchema]), and through [NoOpDatabaseErrorHandler] so a corrupt file is never deleted.
     *
     * @return `true` if the file reports `ok`, `false` if it reports problems or opening/checking it
     * fails as not-a-database/corrupt, or `null` for any other failure.
     */
    private fun cloudIntegrityOk(dbPath: String): Boolean? =
        try {
            SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE, NoOpDatabaseErrorHandler).use { db ->
                val rows = buildList {
                    db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                        while (cursor.moveToNext()) add(cursor.getString(0).orEmpty())
                    }
                }
                if (rows.isEmpty()) null else rows == listOf(QUICK_CHECK_OK)
            }
        } catch (_: SQLiteDatabaseCorruptException) {
            false
        } catch (_: SQLiteException) {
            null
        }

    /**
     * Validates that the database at [dbPath] contains the required tables and columns for [schemaVersion].
     *
     * @param dbPath The path to the database to validate.
     * @param schemaVersion The schema version whose structure is required.
     * @return `true` if the database contains all required tables and columns, `false` if
     * inspection completes and finds one missing, or `null` if [schemaVersion] has no registered
     * expectation in [MergeSchema.EXPECTED_SCHEMAS], or if opening the database or inspecting its
     * tables fails — a failed inspection says nothing about whether the schema itself is valid, so
     * it must not be conflated with a completed inspection that finds it invalid.
     */
    actual fun validateSchema(dbPath: String, schemaVersion: Long): Boolean? {
        val expectedTables = MergeSchema.EXPECTED_SCHEMAS[schemaVersion] ?: return null
        return try {
            // Non-creating: this is only ever called with the downloaded cloud file (see
            // classifyMergeFailure's validateCloudSchema), and openOrCreateDatabase would
            // silently create an empty file for a genuinely-missing one, making a transient
            // "file not found" look exactly like "cloud schema is missing tables" below.
            SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE, NoOpDatabaseErrorHandler).use { db ->
                expectedTables.all { (tableName, requiredColumns) ->
                    val actualColumns = buildSet {
                        db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
                            val nameIndex = cursor.getColumnIndex("name")
                            while (cursor.moveToNext()) {
                                add(cursor.getString(nameIndex).lowercase())
                            }
                        }
                    }
                    requiredColumns.all { it.lowercase() in actualColumns }
                }
            }
        } catch (_: SQLiteException) {
            null
        }
    }

    /**
     * If the downloaded cloud DB is older than the local schema, run the SQLDelight migrations on
     * it (in place, on the temp file) so it matches the local schema before merging.
     * A cloud DB newer than the local schema is rejected (the caller must update the app).
     */
    private fun migrateCloudIfOlder(cloudDbPath: String, localSchemaVersion: Long) {
        // Non-creating, same reason as validateSchema above: a missing cloud file must surface as
        // a failure here, not silently become an empty (version 0) database that the merge below
        // then fails against with a misleading "no such table".
        val cloudVersion = SQLiteDatabase.openDatabase(cloudDbPath, null, SQLiteDatabase.OPEN_READWRITE, NoOpDatabaseErrorHandler).use { db ->
            db.userVersion()
        }
        if (cloudVersion > localSchemaVersion) {
            throw SchemaVersionException(localVersion = localSchemaVersion, cloudVersion = cloudVersion)
        }
        if (cloudVersion in 1 until localSchemaVersion) {
            // The connection above was closed by `.use {}` before this reopens the same file —
            // AndroidSqliteDriver.close() on the raw-database constructor variant used below also
            // closes the SupportSQLiteDatabase it wraps, so this must be its own fresh open, not a
            // reuse of an already-closed connection. Never hold two connections to the same cloud
            // file open at once here: requery's own in-process lock table treats a second open as
            // contention, which can surface as `database is locked` well before SQLite's own
            // busy_timeout would apply (that's a cross-*process* mechanism; this is same-process).
            val db = SQLiteDatabase.openDatabase(cloudDbPath, null, SQLiteDatabase.OPEN_READWRITE, NoOpDatabaseErrorHandler)
            val driver = AndroidSqliteDriver(db)
            try {
                KeryxDatabase.Schema.migrate(driver, cloudVersion, localSchemaVersion)
                driver.execute(null, "PRAGMA user_version=$localSchemaVersion;", 0)
            } finally {
                // Closes the underlying SupportSQLiteDatabase too (confirmed by disassembling
                // AndroidSqliteDriver.close(): it closes openHelper if present, otherwise the
                // database directly — the raw-database constructor never sets an openHelper).
                driver.close()
            }
        }
    }

    private const val TAG = "DatabaseMerger"

    /** Marks the start of the merge transaction; see [mainRuledOut]. */
    private const val MERGE_SAVEPOINT = "keryx_merge"

    /** `PRAGMA quick_check`'s single result row for an intact database. */
    private const val QUICK_CHECK_OK = "ok"
}
