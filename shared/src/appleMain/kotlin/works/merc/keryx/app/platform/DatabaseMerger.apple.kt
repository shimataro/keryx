package works.merc.keryx.app.platform

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.JournalMode
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.SQLITE_BUSY_TIMEOUT_MS
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.DeferredRerunOutcome
import works.merc.keryx.app.domain.ForeignKeyViolation
import works.merc.keryx.app.domain.MainRuledOutVerdict
import works.merc.keryx.app.domain.MergeFailureClassifier
import works.merc.keryx.app.domain.KERYX_SCHEMA_CALLBACKS
import works.merc.keryx.app.domain.MergeSchema
import works.merc.keryx.app.domain.SqliteFailureCategory
import works.merc.keryx.app.domain.TransportSchema

/**
 * Apple ATTACH-DATABASE merge: the same steps as the desktop and Android actuals (see
 * `.claude/rules/sync-merge.md`), on one dedicated [RawSqliteConnection] so the `ATTACH` survives to
 * every merge statement.
 */
actual object DatabaseMerger {
    actual fun merge(
        localDbPath: String,
        cloudDbPath: String,
        localSchemaVersion: Long,
        mergeStatements: List<String>,
    ) = mergeChecked(
        localDbPath = localDbPath,
        cloudDbPath = cloudDbPath,
        supportedVersion = localSchemaVersion,
        mergeStatements = mergeStatements,
        migrateCloud = { path -> migrateCloudIfOlder(path, localSchemaVersion) },
        validateCloudSchema = { path -> validateSchema(path, localSchemaVersion) },
    )

    actual fun mergeTransport(
        localDbPath: String,
        transportPath: String,
        mergeStatements: List<String>,
    ) = mergeChecked(
        localDbPath = localDbPath,
        cloudDbPath = transportPath,
        supportedVersion = TransportSchema.VERSION,
        mergeStatements = mergeStatements,
        migrateCloud = TransportFiles::prepareForMerge,
        validateCloudSchema = TransportFiles::schemaMatches,
    )

    /**
     * The merge for either kind of cloud file: [supportedVersion] is the highest `user_version`
     * accepted, [migrateCloud] brings the file up to it (touching only that file), and
     * [validateCloudSchema] resolves the ambiguous `SQLITE_ERROR` case against the file's expected
     * schema.
     */
    private fun mergeChecked(
        localDbPath: String,
        cloudDbPath: String,
        supportedVersion: Long,
        mergeStatements: List<String>,
        migrateCloud: (cloudDbPath: String) -> Unit,
        validateCloudSchema: (cloudDbPath: String) -> Boolean?,
    ) {
        val verdict = MainRuledOutVerdict()
        try {
            mergeUnclassified(localDbPath, cloudDbPath, supportedVersion, mergeStatements, migrateCloud, verdict)
        } catch (e: SchemaVersionException) {
            // Already classified — must not fall into the catch-all below (it needs an app
            // update, not a cloud-data reset).
            throw e
        } catch (e: Throwable) {
            throw classifyMergeFailure(e, cloudDbPath, validateCloudSchema, verdict.mainRuledOutFor(e))
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
        migrateCloud: (cloudDbPath: String) -> Unit,
        verdict: MainRuledOutVerdict,
    ) {
        try {
            migrateCloud(cloudDbPath)
        } catch (e: Throwable) {
            // This phase touches only the downloaded cloud temp file, never main, so main cannot be
            // the cause of anything it throws (e.g. a constraint failure while migrating the cloud
            // file): record that verdict for the classification step.
            verdict.record(e, mainRuledOut = true)
            throw e
        }

        RawSqliteConnection.open(localDbPath).use { db ->
            db.exec("PRAGMA foreign_keys=ON")
            db.busyTimeout(SQLITE_BUSY_TIMEOUT_MS)
            db.exec("ATTACH DATABASE '${cloudDbPath.replace("'", "''")}' AS cloud")
            try {
                val cloudVersion = db.queryLong("PRAGMA cloud.user_version")
                if (cloudVersion > localSchemaVersion) {
                    throw SchemaVersionException(localVersion = localSchemaVersion, cloudVersion = cloudVersion)
                }
                db.exec("BEGIN")
                // The merge statement running (or that ran last); see mainRuledOut.
                var statementIndex = 0
                try {
                    for ((index, sql) in mergeStatements.withIndex()) {
                        statementIndex = index
                        db.exec(sql)
                    }
                    db.exec("COMMIT")
                } catch (e: Throwable) {
                    // Must run before the ROLLBACK below: only while the failed transaction (and
                    // its write lock on main) is still held can no other writer change main
                    // between the failure and the check.
                    val code = e.findSqliteResultCode()
                    if (code != null && failureCategory(code) == SqliteFailureCategory.CONSTRAINT) {
                        verdict.record(e, db.mainRuledOut(MergeFailureClassifier.remainingStatements(mergeStatements, statementIndex), e))
                    }
                    // A rollback failure must not replace (and hide) the failure being classified.
                    runCatching { db.exec("ROLLBACK") }.exceptionOrNull()?.let(e::addSuppressed)
                    throw e
                }
            } finally {
                runCatching { db.exec("DETACH DATABASE cloud") }
            }
        }
    }

    /**
     * Classifies a merge failure as a permanently-unusable cloud DB
     * ([CloudDataIncompatibleException]) or leaves it unchanged (transient / an app bug), from the
     * SQLite result code found in its cause chain — the same policy as the desktop actual. A failure
     * with no SQLite result code behind it is rethrown unchanged.
     */
    private fun classifyMergeFailure(
        e: Throwable,
        cloudDbPath: String,
        validateCloudSchema: (cloudDbPath: String) -> Boolean?,
        mainRuledOut: Boolean?,
    ): Throwable {
        val code = e.findSqliteResultCode() ?: return e
        val category = failureCategory(code)
        val codeName = sqliteResultCodeName(code)
        val classified: CloudDataIncompatibleException? = MergeFailureClassifier.classify(
            category = category,
            errorCodeName = codeName,
            mainRuledOut = mainRuledOut,
            validateCloudSchema = { validateCloudSchema(cloudDbPath) },
            cloudIntegrityOk = { cloudIntegrityOk(cloudDbPath) },
        )
        if (classified == null) {
            if (category == SqliteFailureCategory.CONSTRAINT || category == SqliteFailureCategory.CORRUPT) {
                Log.warn(
                    TAG,
                    "Merge failure not pinned on the cloud DB (category=$category, code=$code $codeName, " +
                        "mainRuledOut=$mainRuledOut): ${e.message}",
                )
            }
            return e
        }
        Log.warn(TAG, "${classified.message} (category=$category, code=$code $codeName): ${e.message}")
        return classified
    }

    /** Same mapping as the desktop actual, on the primary result code (an extended code's low byte). */
    internal fun failureCategory(resultCode: Int): SqliteFailureCategory =
        when (resultCode and 0xFF) {
            SQLITE_CONSTRAINT -> SqliteFailureCategory.CONSTRAINT
            SQLITE_NOTADB, SQLITE_CORRUPT, SQLITE_FORMAT, SQLITE_EMPTY -> SqliteFailureCategory.CORRUPT
            SQLITE_ERROR -> SqliteFailureCategory.STATEMENT_ERROR
            else -> SqliteFailureCategory.OTHER
        }

    /**
     * Whether the local `main` DB is ruled out as the cause of the constraint failure [failure] —
     * [MergeFailureClassifier.mainRuledOut]'s policy, fed with evidence gathered on this (the merge's)
     * connection while the failed merge transaction is still open. The re-run runs
     * [remainingStatements] (the failed statement and every one after it — see
     * [MergeFailureClassifier.mainRuledOut] for why that gives the right verdict); the caller then
     * rolls the whole transaction back.
     *
     * Each piece of evidence is gathered only while SQLite still has the transaction open
     * (`sqlite3_get_autocommit`, which also reflects a rollback SQLite performed by itself), and the
     * verdict counts only if it still has at the end: otherwise some of the evidence may have been
     * read from a `main` another writer could already have changed.
     *
     * @return `true` / `false` as [MergeFailureClassifier.mainRuledOut], or `null` when undetermined;
     * a failure of any check is added to [failure] as suppressed, never replacing it.
     */
    private fun RawSqliteConnection.mainRuledOut(remainingStatements: List<String>, failure: Throwable): Boolean? {
        val ruledOut = try {
            MergeFailureClassifier.mainRuledOut(
                // RawSqliteConnection's SqliteException carries the extended result code; SQLiter's
                // exception only the primary one, which maps to null (the full path).
                isForeignKeyFailure = failure.findSqliteResultCode()?.let(MergeFailureClassifier::isForeignKeyFailure),
                readViolationsBefore = { if (inTransaction()) foreignKeyViolations() else null },
            ) { rerunWithDeferredForeignKeys(remainingStatements, failure) }
        } catch (checkFailure: Throwable) {
            failure.addSuppressed(checkFailure)
            null
        }
        return if (inTransaction()) ruledOut else null
    }

    /**
     * Runs [remainingStatements] again with FOREIGN KEY checks deferred (`PRAGMA defer_foreign_keys`,
     * which SQLite switches off again when the transaction ends) — see
     * [MergeFailureClassifier.mainRuledOut]. Its own SQL failures other than a re-run statement's are
     * thrown to [mainRuledOut].
     *
     * Runs nothing unless SQLite still has the merge transaction open, so no statement can run outside
     * it and commit straight into main. Nothing else uses this connection meanwhile, and SQLite ends a
     * transaction by itself only on a statement's error, which stops the re-run there.
     */
    private fun RawSqliteConnection.rerunWithDeferredForeignKeys(
        remainingStatements: List<String>,
        failure: Throwable,
    ): DeferredRerunOutcome {
        if (!inTransaction()) return DeferredRerunOutcome.Undetermined
        exec("PRAGMA defer_foreign_keys=ON")
        try {
            for (sql in remainingStatements) exec(sql)
        } catch (rerunFailure: Throwable) {
            val code = rerunFailure.findSqliteResultCode()
            if (code != null && failureCategory(code) == SqliteFailureCategory.CONSTRAINT) {
                return DeferredRerunOutcome.ConstraintFailed
            }
            failure.addSuppressed(rerunFailure)
            return DeferredRerunOutcome.Undetermined
        }
        return if (inTransaction()) DeferredRerunOutcome.Completed(foreignKeyViolations()) else DeferredRerunOutcome.Undetermined
    }

    /** `PRAGMA main.foreign_key_check`'s rows. */
    private fun RawSqliteConnection.foreignKeyViolations(): Set<ForeignKeyViolation> =
        queryRowsStrict("PRAGMA main.foreign_key_check").mapTo(mutableSetOf()) { row ->
            // table, parent and fkid are never NULL; a row that breaks that throws, which mainRuledOut
            // treats as a failed check (undetermined).
            ForeignKeyViolation(
                table = checkNotNull(row[0]) { "foreign_key_check: NULL table" },
                rowId = row[1]?.toLong(),
                parent = checkNotNull(row[2]) { "foreign_key_check: NULL parent" },
                foreignKeyId = checkNotNull(row[3]) { "foreign_key_check: NULL fkid" }.toLong(),
            )
        }

    /**
     * Runs `PRAGMA quick_check` against the downloaded cloud file — see
     * [MergeFailureClassifier.classify]'s `cloudIntegrityOk`. [RawSqliteConnection.open] does not
     * create a missing file, so that is a failure (undetermined), not an intact empty database.
     *
     * @return `true` if the file reports `ok`, `false` if it reports problems or opening/checking it
     * fails as not-a-database/corrupt, or `null` for any other failure.
     */
    private fun cloudIntegrityOk(dbPath: String): Boolean? =
        try {
            RawSqliteConnection.open(dbPath).use { db ->
                val rows = db.queryFirstColumnStrict("PRAGMA quick_check")
                if (rows.isEmpty()) null else rows == listOf(QUICK_CHECK_OK)
            }
        } catch (e: SqliteException) {
            val code = e.resultCode and 0xFF
            if (code == SQLITE_NOTADB || code == SQLITE_CORRUPT) false else null
        }

    actual fun validateSchema(dbPath: String, schemaVersion: Long): Boolean? {
        val expectedTables = MergeSchema.EXPECTED_SCHEMAS[schemaVersion] ?: return null
        return try {
            RawSqliteConnection.open(dbPath).use { db ->
                expectedTables.all { (tableName, requiredColumns) ->
                    val actualColumns = db.queryColumn("PRAGMA table_info($tableName)", "name").map { it.lowercase() }.toSet()
                    requiredColumns.all { it.lowercase() in actualColumns }
                }
            }
        } catch (_: SqliteException) {
            null
        }
    }

    /**
     * Brings an older cloud file up to [localSchemaVersion] with the app's own migrations before it
     * is attached. Opened through [NativeSqliteDriver] only for that migration, in rollback-journal
     * mode so the downloaded file is not switched to WAL.
     *
     * The driver opens (and so migrates) the file lazily, on its first statement — see
     * `DatabaseDriverFactory.createDriver` — so one trivial query is run before it is closed.
     *
     * @throws IllegalStateException if the file is still not at [localSchemaVersion] afterwards.
     * Deliberately carries no SQLite result code, so [merge] leaves it unclassified (transient),
     * never offering a destructive cloud-data reset for what may be an app-side migration fault.
     */
    private fun migrateCloudIfOlder(cloudDbPath: String, localSchemaVersion: Long) {
        val cloudVersion = RawSqliteConnection.userVersionOf(cloudDbPath)
        if (cloudVersion > localSchemaVersion) {
            throw SchemaVersionException(localVersion = localSchemaVersion, cloudVersion = cloudVersion)
        }
        if (cloudVersion in 1 until localSchemaVersion) {
            val dir = cloudDbPath.substringBeforeLast('/')
            val name = cloudDbPath.substringAfterLast('/')
            val driver = NativeSqliteDriver(
                schema = KeryxDatabase.Schema,
                name = name,
                // One read-only reader connection, stated explicitly (it is the driver's default): reads outside a transaction share it.
                maxReaderConnections = 1,
                onConfiguration = { config ->
                    config.copy(
                        journalMode = JournalMode.DELETE,
                        extendedConfig = config.extendedConfig.copy(basePath = dir),
                    )
                },
                callbacks = KERYX_SCHEMA_CALLBACKS,
            )
            try {
                driver.executeQuery(null, "SELECT 1", { QueryResult.Unit }, 0)
            } finally {
                driver.close()
            }
            val migratedVersion = RawSqliteConnection.userVersionOf(cloudDbPath)
            check(migratedVersion == localSchemaVersion) {
                "Cloud DB migration did not reach schema version $localSchemaVersion " +
                    "(from $cloudVersion, now $migratedVersion)"
            }
        }
    }

    private const val TAG = "DatabaseMerger"

    /** `PRAGMA quick_check`'s single result row for an intact database. */
    private const val QUICK_CHECK_OK = "ok"

    private const val SQLITE_ERROR = 1
    private const val SQLITE_CORRUPT = 11
    private const val SQLITE_EMPTY = 16
    private const val SQLITE_CONSTRAINT = 19
    private const val SQLITE_FORMAT = 24
    private const val SQLITE_NOTADB = 26
}
