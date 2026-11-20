package works.merc.keryx.app.platform

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.sqlite.SQLiteCommitListener
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteException
import org.sqlite.SQLiteOpenMode
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.SQLITE_BUSY_TIMEOUT_MS
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.DeferredRerunOutcome
import works.merc.keryx.app.domain.ForeignKeyViolation
import works.merc.keryx.app.domain.MainRuledOutVerdict
import works.merc.keryx.app.domain.MergeFailureClassifier
import works.merc.keryx.app.domain.MergeSchema
import works.merc.keryx.app.domain.SqliteFailureCategory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

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
     * violates this app's schema constraints (classified from SQLite's error code plus evidence
     * pinning it on the cloud — see [classifyMergeFailure]). Any other failure is rethrown
     * unchanged (transient / an app bug / a local-side fault).
     */
    actual fun merge(
        localDbPath: String,
        cloudDbPath: String,
        localSchemaVersion: Long,
        mergeStatements: List<String>,
    ) = merge(localDbPath, cloudDbPath, localSchemaVersion, mergeStatements, ::migrateCloudIfOlder)

    /**
     * [merge] with its cloud-only phase supplied by the caller — [migrateCloudIfOlder] in
     * production. `internal` so a test can make that phase fail with a genuine SQLite error the
     * real migration cannot be driven to (e.g. a constraint failure), and check how it is classified.
     *
     * @param migrateCloud brings the cloud file at the given path up to the given schema version;
     * it must touch only that file, never the local DB.
     */
    internal fun merge(
        localDbPath: String,
        cloudDbPath: String,
        localSchemaVersion: Long,
        mergeStatements: List<String>,
        migrateCloud: (cloudDbPath: String, localSchemaVersion: Long) -> Unit,
    ) {
        val verdict = MainRuledOutVerdict()
        try {
            mergeUnclassified(localDbPath, cloudDbPath, localSchemaVersion, mergeStatements, migrateCloud, verdict)
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
     * @param migrateCloud the cloud-only phase (see the internal [merge]).
     * @param verdict receives the "main is not the cause" verdict: `true` for any failure of the
     * cloud-only phase ([migrateCloud], which never touches main), and for a constraint failure of
     * the merge statements the verdict computed inside the failed transaction before its rollback
     * (see [mainRuledOut]).
     */
    private fun mergeUnclassified(
        localDbPath: String,
        cloudDbPath: String,
        localSchemaVersion: Long,
        mergeStatements: List<String>,
        migrateCloud: (cloudDbPath: String, localSchemaVersion: Long) -> Unit,
        verdict: MainRuledOutVerdict,
    ) {
        // Bring an older cloud DB up to the local schema first, so the merge statements can
        // reference columns added in newer versions without a "no such column" failure.
        try {
            migrateCloud(cloudDbPath, localSchemaVersion)
        } catch (e: Throwable) {
            // This phase touches only the downloaded cloud temp file, never main, so main cannot be
            // the cause of anything it throws (e.g. a constraint failure while migrating the cloud
            // file): record that verdict for the classification step.
            verdict.record(e, mainRuledOut = true)
            throw e
        }

        val connection = DriverManager.getConnection("jdbc:sqlite:$localDbPath")
        try {
            connection.autoCommit = true
            connection.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys=ON")
                st.execute("PRAGMA busy_timeout=$SQLITE_BUSY_TIMEOUT_MS")
                // ATTACH must run outside a transaction.
                st.execute("ATTACH DATABASE '${cloudDbPath.replace("'", "''")}' AS cloud")
            }

            val cloudVersion = connection.createStatement().use { st ->
                st.executeQuery("PRAGMA cloud.user_version").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
            if (cloudVersion > localSchemaVersion) {
                throw SchemaVersionException(localVersion = localSchemaVersion, cloudVersion = cloudVersion)
            }

            connection.autoCommit = false
            // JDBC's autoCommit flag only says what was asked for: SQLite can end the transaction
            // by itself (an implicit rollback after certain errors, or a statement's own
            // ON CONFLICT ROLLBACK). Its rollback hook fires for those too, so this records whether
            // the merge transaction is still the one SQLite has open.
            val transactionEnded = TransactionEndListener()
            val sqliteConnection = connection.unwrap(SQLiteConnection::class.java)
            sqliteConnection.addCommitListener(transactionEnded)
            var failure: Throwable? = null
            // The merge statement running (or that ran last); see mainRuledOut.
            var statementIndex = 0
            try {
                connection.createStatement().use { st ->
                    for ((index, sql) in mergeStatements.withIndex()) {
                        statementIndex = index
                        st.execute(sql)
                    }
                }
                connection.commit()
            } catch (e: Throwable) {
                // Must run before the rollback below: only while the failed transaction (and its
                // write lock on main) is still held can no other writer change main between the
                // failure and the check.
                if (e.findSqliteCause()?.failureCategory() == SqliteFailureCategory.CONSTRAINT) {
                    verdict.record(
                        e,
                        connection.mainRuledOut(
                            transactionEnded,
                            MergeFailureClassifier.remainingStatements(mergeStatements, statementIndex),
                            e,
                        ),
                    )
                }
                // A rollback failure must not replace (and hide) the failure being classified.
                runCatching { connection.rollback() }.exceptionOrNull()?.let(e::addSuppressed)
                failure = e
                throw e
            } finally {
                sqliteConnection.removeCommitListener(transactionEnded)
                // Restoring autoCommit commits/ends whatever transaction the driver thinks is open,
                // which fails once SQLite has already ended it; that failure must not replace (and
                // hide) the merge failure being classified either.
                runCatching { connection.autoCommit = true }.exceptionOrNull()?.let { restoreFailure ->
                    failure?.addSuppressed(restoreFailure) ?: throw restoreFailure
                }
            }
        } finally {
            runCatching {
                connection.createStatement().use { it.execute("DETACH DATABASE cloud") }
            }
            connection.close()
        }
    }

    /**
     * Classifies a merge failure as a permanently-unusable cloud DB
     * ([CloudDataIncompatibleException]) or leaves it unchanged (transient / an app bug).
     *
     * This side only talks to the JDBC driver: it walks the cause chain for a [SQLiteException]
     * and reduces its error code — not message text, which is locale- and driver-version-fragile —
     * to a driver-independent [SqliteFailureCategory]. The classification policy itself lives in
     * [MergeFailureClassifier] (`commonMain`), so another target's driver only has to produce the
     * same category.
     */
    private fun classifyMergeFailure(
        e: Throwable,
        cloudDbPath: String,
        localSchemaVersion: Long,
        mainRuledOut: Boolean?,
    ): Throwable {
        val sqliteCause = e.findSqliteCause() ?: return e
        val category = sqliteCause.failureCategory()
        val classified = MergeFailureClassifier.classify(
            category = category,
            errorCodeName = sqliteCause.resultCode.name,
            mainRuledOut = mainRuledOut,
            validateCloudSchema = { validateSchema(cloudDbPath, localSchemaVersion) },
            cloudIntegrityOk = { cloudIntegrityOk(cloudDbPath) },
        )
        if (classified == null) {
            if (category == SqliteFailureCategory.CONSTRAINT || category == SqliteFailureCategory.CORRUPT) {
                Log.warn(
                    TAG,
                    "Merge failure not pinned on the cloud DB (category=$category, " +
                        "code=${sqliteCause.resultCode.name}, mainRuledOut=$mainRuledOut): ${e.message}",
                )
            }
            return e
        }
        // The classified exception's own message is the diagnosis; the original failure's message
        // carries the SQL-level detail behind it. category/resultCode are logged explicitly
        // because e.message may come from a wrapper exception (see findSqliteCause) that omits
        // the underlying SQLite result code.
        Log.warn(
            TAG,
            "${classified.message} (category=$category, code=${sqliteCause.resultCode.name}): ${e.message}",
        )
        return classified
    }

    /** Reduces this JDBC exception's SQLite *primary* result code to a platform-independent category. */
    private fun SQLiteException.failureCategory(): SqliteFailureCategory =
        when (resultCode.code and 0xFF) {
            SQLITE_CONSTRAINT -> SqliteFailureCategory.CONSTRAINT
            SQLITE_NOTADB, SQLITE_CORRUPT, SQLITE_FORMAT, SQLITE_EMPTY -> SqliteFailureCategory.CORRUPT
            SQLITE_ERROR -> SqliteFailureCategory.STATEMENT_ERROR
            else -> SqliteFailureCategory.OTHER
        }

    /**
     * Whether this constraint failure is a FOREIGN KEY violation, from its *extended* result code
     * (sqlite-jdbc reports extended codes) — see [MergeFailureClassifier.isForeignKeyFailure].
     */
    private fun SQLiteException.isForeignKeyFailure(): Boolean? =
        MergeFailureClassifier.isForeignKeyFailure(resultCode.code)

    /**
     * Whether the local `main` DB is ruled out as the cause of the constraint failure [failure] —
     * [MergeFailureClassifier.mainRuledOut]'s policy, fed with evidence gathered on this (the merge's)
     * connection while the failed merge transaction is still open. The re-run runs
     * [remainingStatements] (the failed statement and every one after it — see
     * [MergeFailureClassifier.mainRuledOut] for why that gives the right verdict); the caller then
     * rolls the whole transaction back.
     *
     * Each piece of evidence is gathered only while the merge transaction is still the one SQLite has
     * open ([transactionEnded], the rollback hook, which also sees a rollback SQLite performed by
     * itself), and the verdict counts only if it still is at the end: otherwise some of the evidence
     * may have been read from a `main` another writer could already have changed.
     *
     * @return `true` / `false` as [MergeFailureClassifier.mainRuledOut], or `null` when undetermined;
     * a failure of any check is added to [failure] as suppressed, never replacing it.
     */
    private fun Connection.mainRuledOut(
        transactionEnded: TransactionEndListener,
        remainingStatements: List<String>,
        failure: Throwable,
    ): Boolean? {
        val transactionOpen = { !autoCommit && !transactionEnded.ended }
        val ruledOut = try {
            MergeFailureClassifier.mainRuledOut(
                isForeignKeyFailure = failure.findSqliteCause()?.isForeignKeyFailure(),
                readViolationsBefore = { if (transactionOpen()) foreignKeyViolations() else null },
            ) {
                rerunWithDeferredForeignKeys(remainingStatements, transactionOpen, failure)
            }
        } catch (checkFailure: Throwable) {
            failure.addSuppressed(checkFailure)
            null
        }
        return if (transactionOpen()) ruledOut else null
    }

    /**
     * Runs [remainingStatements] again with FOREIGN KEY checks deferred (`PRAGMA defer_foreign_keys`,
     * which SQLite switches off again when the transaction ends) — see
     * [MergeFailureClassifier.mainRuledOut]. Its own SQL failures other than a re-run statement's are
     * thrown to [mainRuledOut].
     *
     * Runs nothing unless the merge transaction is still open, so no statement can run outside it and
     * commit straight into main. Nothing else uses this connection meanwhile, and SQLite ends a
     * transaction by itself only on a statement's error, which stops the re-run there.
     */
    private fun Connection.rerunWithDeferredForeignKeys(
        remainingStatements: List<String>,
        transactionOpen: () -> Boolean,
        failure: Throwable,
    ): DeferredRerunOutcome {
        if (!transactionOpen()) return DeferredRerunOutcome.Undetermined
        createStatement().use { st ->
            st.execute("PRAGMA defer_foreign_keys=ON")
            try {
                for (sql in remainingStatements) st.execute(sql)
            } catch (rerunFailure: Throwable) {
                if (rerunFailure.findSqliteCause()?.failureCategory() == SqliteFailureCategory.CONSTRAINT) {
                    return DeferredRerunOutcome.ConstraintFailed
                }
                failure.addSuppressed(rerunFailure)
                return DeferredRerunOutcome.Undetermined
            }
        }
        return if (transactionOpen()) DeferredRerunOutcome.Completed(foreignKeyViolations()) else DeferredRerunOutcome.Undetermined
    }

    /** `PRAGMA main.foreign_key_check`'s rows. */
    private fun Connection.foreignKeyViolations(): Set<ForeignKeyViolation> =
        createStatement().use { st ->
            st.executeQuery("PRAGMA main.foreign_key_check").use { rs ->
                buildSet {
                    while (rs.next()) {
                        val table = rs.getString(1)
                        val rowId = rs.getLong(2).takeUnless { rs.wasNull() }
                        add(ForeignKeyViolation(table, rowId, rs.getString(3), rs.getLong(4)))
                    }
                }
            }
        }

    /** Records that SQLite ended the transaction it was registered during, by commit or by rollback. */
    private class TransactionEndListener : SQLiteCommitListener {
        @Volatile
        var ended = false
            private set

        override fun onCommit() {
            ended = true
        }

        override fun onRollback() {
            ended = true
        }
    }

    /**
     * Runs `PRAGMA quick_check` against the downloaded cloud file — see
     * [MergeFailureClassifier.classify]'s `cloudIntegrityOk`. Opened without `CREATE`, so a missing
     * file is a failure (undetermined) rather than a fresh, intact, empty database.
     *
     * @return `true` if the file reports `ok`, `false` if it reports problems or opening/checking it
     * fails as not-a-database/corrupt, or `null` for any other failure.
     */
    private fun cloudIntegrityOk(dbPath: String): Boolean? {
        val properties = SQLiteConfig().apply {
            setOpenMode(SQLiteOpenMode.READWRITE)
            resetOpenMode(SQLiteOpenMode.CREATE)
        }.toProperties()
        return try {
            DriverManager.getConnection("jdbc:sqlite:$dbPath", properties).use { connection ->
                connection.createStatement().use { st ->
                    st.executeQuery("PRAGMA quick_check").use { rs ->
                        if (rs.next()) rs.getString(1) == QUICK_CHECK_OK && !rs.next() else null
                    }
                }
            }
        } catch (e: SQLException) {
            val code = (e as? SQLiteException)?.resultCode?.code?.and(0xFF)
            if (code == SQLITE_NOTADB || code == SQLITE_CORRUPT) false else null
        }
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
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
                expectedTables.all { (tableName, requiredColumns) ->
                    val actualColumns = connection.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA table_info($tableName)").use { rs ->
                            buildSet {
                                while (rs.next()) {
                                    add(rs.getString("name").lowercase())
                                }
                            }
                        }
                    }
                    requiredColumns.all { it.lowercase() in actualColumns }
                }
            }
        } catch (_: SQLException) {
            null
        }
    }

    /**
     * If the downloaded cloud DB is older than the local schema, run the SQLDelight migrations on
     * it (in place, on the temp file) so it matches the local schema before merging.
     * A cloud DB newer than the local schema is rejected (the caller must update the app).
     */
    private fun migrateCloudIfOlder(cloudDbPath: String, localSchemaVersion: Long) {
        val cloudVersion = DriverManager.getConnection("jdbc:sqlite:$cloudDbPath").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
        if (cloudVersion > localSchemaVersion) {
            throw SchemaVersionException(localVersion = localSchemaVersion, cloudVersion = cloudVersion)
        }
        if (cloudVersion in 1 until localSchemaVersion) {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$cloudDbPath", Properties())
            try {
                KeryxDatabase.Schema.migrate(driver, cloudVersion, localSchemaVersion)
                driver.execute(null, "PRAGMA user_version=$localSchemaVersion;", 0)
            } finally {
                driver.close()
            }
        }
    }

    private const val TAG = "DatabaseMerger"

    /** `PRAGMA quick_check`'s single result row for an intact database. */
    private const val QUICK_CHECK_OK = "ok"

    // SQLite *primary* result codes (an extended code's low byte, i.e. `code and 0xFF`), named
    // locally so the classification `when` above reads without a lookup table. See
    // org.sqlite.SQLiteErrorCode for the full list.
    private const val SQLITE_ERROR = 1
    private const val SQLITE_CORRUPT = 11
    private const val SQLITE_EMPTY = 16
    private const val SQLITE_CONSTRAINT = 19
    private const val SQLITE_FORMAT = 24
    private const val SQLITE_NOTADB = 26
}
