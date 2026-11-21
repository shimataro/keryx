package works.merc.keryx.app.platform

import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.domain.BaseCoverage
import works.merc.keryx.app.domain.DeltaExportSql
import works.merc.keryx.app.domain.MergeSchema
import works.merc.keryx.app.domain.ReduceSql
import works.merc.keryx.app.domain.SqliteFailureCategory
import works.merc.keryx.app.domain.SyncValidationSql
import works.merc.keryx.app.domain.TableSpec
import works.merc.keryx.app.domain.TransportFileKind
import works.merc.keryx.app.domain.TransportFileName
import works.merc.keryx.app.domain.TransportMeta
import works.merc.keryx.app.domain.TransportSchema
import works.merc.keryx.app.domain.TransportVector

/** How [TransportConnections.open] opens a file. */
enum class TransportOpenMode {
    /** Read-only; the file must exist. */
    READ_ONLY,

    /** Read-write; the file must exist. */
    READ_WRITE,

    /** Read-write, creating the file when it does not exist. */
    CREATE,
}

/**
 * One dedicated SQLite connection to a transport (or downloaded pre-delta) file — outside the app's
 * SQLDelight driver, for the same reason as [DatabaseMerger]: an `ATTACH` and a transaction must stay
 * on one connection.
 */
interface TransportConnection : AutoCloseable {
    /** Runs one statement, discarding any rows. */
    fun exec(sql: String)

    /** Every row of [sql], each column as text (`null` for SQL NULL). */
    fun queryRows(sql: String): List<List<String?>>

    /** Runs [block] in a transaction: committed when it returns, rolled back when it throws. */
    fun <T> transaction(block: () -> T): T
}

/**
 * Per-platform connections for [TransportFiles]: JDBC on desktop, requery's `SQLiteDatabase` on
 * Android, [RawSqliteConnection] on Apple — the same per-platform connection code as
 * [DatabaseMerger].
 */
expect object TransportConnections {
    /** Opens [path] in [mode]. Throws the platform's SQLite exception on failure. */
    fun open(path: String, mode: TransportOpenMode): TransportConnection

    /**
     * The category of the SQLite failure behind [failure] (searching its cause chain), or `null` when
     * none is found.
     */
    fun failureCategory(failure: Throwable): SqliteFailureCategory?
}

/** What a downloaded file is validated as. */
sealed interface ValidationTarget {
    /** A delta or base, named [name] (which already passed [TransportFileName.parse]). */
    data class Transport(val name: TransportFileName) : ValidationTarget

    /** A pre-delta full database, for a build whose local schema is [localSchemaVersion]. */
    data class PreDelta(val localSchemaVersion: Long) : ValidationTarget
}

/**
 * The verdict of [TransportFiles.validate]. Mapped by the caller: [Corrupt] →
 * `CloudDataIncompatibleException`, [Undetermined] → a transient failure (never a reset), [TooNew] →
 * `SchemaVersionException`.
 */
sealed interface TransportValidation {
    data object Valid : TransportValidation

    /** The file is unusable; [reason] is for the log. */
    data class Corrupt(val reason: String) : TransportValidation

    /** The check could not be completed or this build has no expectation for the file's version. */
    data class Undetermined(val reason: String) : TransportValidation

    /** The file's `user_version` is newer than this build understands. */
    data class TooNew(val version: Long) : TransportValidation
}

/**
 * Validation, creation and folding of transport files — see "Validating Cloud Files", "Receiving"
 * and "Bases and Garbage Collection" in `docs/sync-architecture.md`. The SQL lives in
 * [SyncValidationSql] / [ReduceSql]; only the connection is per platform ([TransportConnections]).
 */
object TransportFiles {

    /**
     * Runs "Validating Cloud Files" checks 4–9 on the file at [path] (checks 1–3, the name, the
     * download and the gzip/header/hash, are the caller's). Read-only, except that an older
     * transport file is migrated up in place (check 6) — a pre-delta database is checked against its
     * own version's expectation and migrated later, by [DatabaseMerger.merge].
     *
     * Never throws for a SQL failure: a corrupt or not-a-database file is [TransportValidation.Corrupt],
     * any other failure (the file cannot be opened, I/O) is [TransportValidation.Undetermined].
     */
    fun validate(path: String, target: ValidationTarget): TransportValidation =
        try {
            validateUnchecked(path, target)
        } catch (e: Exception) {
            when (TransportConnections.failureCategory(e)) {
                SqliteFailureCategory.CORRUPT -> TransportValidation.Corrupt("SQLite reports the file corrupt: ${e.message}")
                else -> TransportValidation.Undetermined("validation failed: ${e.message}")
            }
        }

    private fun validateUnchecked(path: String, target: ValidationTarget): TransportValidation {
        val version = TransportConnections.open(path, TransportOpenMode.READ_ONLY).use { it.userVersion() }
        // Check 4.
        val supported = when (target) {
            is ValidationTarget.Transport -> TransportSchema.VERSION
            is ValidationTarget.PreDelta -> target.localSchemaVersion
        }
        if (version > supported) return TransportValidation.TooNew(version)
        val expected = when (target) {
            is ValidationTarget.Transport -> TransportSchema.EXPECTED_TABLES[version]
            is ValidationTarget.PreDelta -> MergeSchema.EXPECTED_TABLES[version]
        } ?: return TransportValidation.Undetermined("no expectation registered for version $version")

        // Check 5. A transport file must match exactly; a pre-delta database also holds device-local columns.
        TransportConnections.open(path, TransportOpenMode.READ_ONLY).use { connection ->
            SyncValidationSql.columnsProblem(
                expected,
                connection.columnsOf(expected),
                rejectExtra = target is ValidationTarget.Transport,
            )
        }?.let { return TransportValidation.Corrupt(it) }

        // Check 6.
        val tables = if (target is ValidationTarget.Transport && version < TransportSchema.VERSION) {
            val migrations = TransportSchema.migrationPath(version)
                ?: return TransportValidation.Undetermined("no transport migration from version $version")
            migrate(path, migrations)?.let { return it }
            TransportSchema.EXPECTED_TABLES.getValue(TransportSchema.VERSION)
        } else {
            expected
        }

        return TransportConnections.open(path, TransportOpenMode.READ_ONLY).use { connection ->
            checkValues(connection, tables, target)
        }
    }

    private fun migrate(path: String, migrations: List<TransportSchema.Migration>): TransportValidation? =
        try {
            TransportConnections.open(path, TransportOpenMode.READ_WRITE).use { connection ->
                connection.transaction {
                    for (migration in migrations) {
                        migration.statements.forEach(connection::exec)
                        connection.exec("PRAGMA user_version = ${migration.to}")
                    }
                }
            }
            null
        } catch (e: Exception) {
            when (TransportConnections.failureCategory(e)) {
                SqliteFailureCategory.CORRUPT, SqliteFailureCategory.CONSTRAINT, SqliteFailureCategory.STATEMENT_ERROR ->
                    TransportValidation.Corrupt("migration failed: ${e.message}")
                else -> TransportValidation.Undetermined("migration failed: ${e.message}")
            }
        }

    /** Checks 7–9 on an up-to-date file. */
    private fun checkValues(connection: TransportConnection, tables: List<TableSpec>, target: ValidationTarget): TransportValidation {
        // Check 7.
        for (table in tables) {
            val column = connection.queryRows(SyncValidationSql.typeViolationQuery(table)).firstOrNull()?.firstOrNull()
            if (column != null) return TransportValidation.Corrupt("${table.name}.$column holds a value of the wrong type or range")
        }
        // Check 8.
        if (target is ValidationTarget.Transport) {
            val meta = connection.queryRows("SELECT key, value FROM ks_meta").associate { it[0].orEmpty() to it[1].orEmpty() }
            SyncValidationSql.metaProblem(meta, target.name)?.let { return TransportValidation.Corrupt(it) }
            val devices = connection.queryRows("SELECT device FROM ks_vector").map { it[0].orEmpty() }
            val covered = connection.queryRows("SELECT name FROM ks_covered").map { it[0].orEmpty() }
            SyncValidationSql.coverageProblem(target.name, devices, covered)?.let { return TransportValidation.Corrupt(it) }
        }
        // Check 9.
        for (check in SyncValidationSql.closureChecks) {
            if (connection.queryRows(check.query).isNotEmpty()) {
                return TransportValidation.Corrupt("dangling reference: ${check.description}")
            }
        }
        return TransportValidation.Valid
    }

    /**
     * Creates an empty transport file of [TransportSchema.VERSION] at [path] carrying [meta]
     * (replacing whatever was there), e.g. the accumulator of a fold.
     */
    fun createEmpty(path: String, meta: TransportMeta) {
        FileIO.delete(path)
        FileIO.delete("$path-journal")
        TransportConnections.open(path, TransportOpenMode.CREATE).use { connection ->
            connection.transaction {
                TransportSchema.ddl.forEach(connection::exec)
                for ((key, value) in meta.rows) {
                    // Keys are constants; values are validated ids and decimals (TransportMeta's init).
                    connection.exec("INSERT INTO ks_meta (key, value) VALUES ('$key', '$value')")
                }
                connection.exec("PRAGMA user_version = ${TransportSchema.VERSION}")
            }
        }
    }

    /**
     * Folds the validated transport file at [inputPath], named [inputName], into the accumulator at
     * [accumulatorPath] ([ReduceSql]) in one transaction: the synced rows, the coverage (a delta adds
     * its own name to `ks_covered`; a base contributes its vector and covered names), and the vector
     * extended over the deltas that continue it ([TransportVector.extend]).
     *
     * @param extendVector whether to extend the vector over the covered deltas right away. A new base
     * passes `false` and calls [finalizeBase] once every input is folded, which extends it only over
     * deltas old enough to be implied; a receive's accumulator, whose coverage is never read, passes
     * `false` too.
     * @throws IllegalStateException when either file is not of [TransportSchema.VERSION] (an input
     * must have been validated, and so migrated, first).
     */
    fun fold(accumulatorPath: String, inputPath: String, inputName: TransportFileName, extendVector: Boolean = true) {
        TransportConnections.open(accumulatorPath, TransportOpenMode.READ_WRITE).use { connection ->
            check(connection.userVersion() == TransportSchema.VERSION) { "The accumulator is not a current transport file" }
            // ATTACH must run outside a transaction.
            connection.exec("ATTACH DATABASE '${inputPath.replace("'", "''")}' AS src")
            try {
                val inputVersion = connection.queryRows("PRAGMA src.user_version").firstOrNull()?.firstOrNull()?.toLongOrNull()
                check(inputVersion == TransportSchema.VERSION) { "The input is not a current transport file" }
                connection.transaction {
                    ReduceSql.all.forEach(connection::exec)
                    ReduceSql.combineCoverage.forEach(connection::exec)
                    if (inputName.kind == TransportFileKind.DELTA) {
                        // A parsed name: lowercase hex, digits, '-' and '.' only.
                        connection.exec(
                            "INSERT INTO main.ks_covered (name, created_at) VALUES ('${inputName.fileName}', " +
                                "(SELECT CAST(value AS INTEGER) FROM src.ks_meta WHERE key = '${TransportSchema.MetaKeys.CREATED_AT}')) " +
                                "ON CONFLICT(name) DO UPDATE SET created_at = ${ReduceSql.EARLIEST_KNOWN_COVERED_AT}",
                        )
                    }
                    if (extendVector) extendVector(connection)
                }
            } finally {
                runCatching { connection.exec("DETACH DATABASE src") }
            }
        }
    }

    private fun extendVector(connection: TransportConnection) {
        val vector = connection.queryRows("SELECT device, seq FROM main.ks_vector")
            .associate { it[0].orEmpty() to (it[1]?.toLongOrNull() ?: 0L) }
        val covered = connection.queryRows("SELECT name FROM main.ks_covered").mapNotNull { it[0]?.let(TransportFileName::parse) }
        val extended = TransportVector.extend(vector, covered)
        for ((device, seq) in extended) {
            if (vector[device] == seq) continue
            // Devices come from parsed names (lowercase UUIDs).
            check(TransportFileName.isSyncId(device)) { "Malformed device in ks_vector" }
            connection.exec(
                "INSERT INTO main.ks_vector (device, seq) VALUES ('$device', $seq) " +
                    "ON CONFLICT(device) DO UPDATE SET seq = MAX(seq, excluded.seq)",
            )
        }
    }

    /**
     * Completes a base folded with `extendVector = false`, in one transaction on one connection:
     *
     * - the vector is extended over the covered deltas that continue it without a gap, **counting only
     *   deltas created at or before [impliableCutoff]** (`ks_covered.created_at`; an unknown time never
     *   counts) — see "Bases and Garbage Collection";
     * - every covered name the extended vector now implies is removed from `ks_covered`, except where
     *   two covered names share a `(device, seq)` (a cloned database): those stay listed, so a
     *   duplicate this base never folded is not implied by its vector ([BaseCoverage.covers]);
     * - tables a platform's connection adds to a file it opens for writing (requery's
     *   `android_metadata`) are dropped, as [DeltaExporter] does.
     *
     * The file is complete afterwards: hash it as it is and never open it for writing again (on
     * Android that would add `android_metadata` back and change its bytes).
     */
    fun finalizeBase(path: String, impliableCutoff: Long) {
        TransportConnections.open(path, TransportOpenMode.READ_WRITE).use { connection ->
            check(connection.userVersion() == TransportSchema.VERSION) { "The base is not a current transport file" }
            connection.transaction {
                val vector = connection.queryRows("SELECT device, seq FROM main.ks_vector")
                    .associate { it[0].orEmpty() to (it[1]?.toLongOrNull() ?: 0L) }
                val rows = connection.queryRows("SELECT name, created_at FROM main.ks_covered")
                val covered = rows.mapNotNull { it[0]?.let(TransportFileName::parse) }
                val impliable = rows.filter { (it[1]?.toLongOrNull() ?: Long.MAX_VALUE) <= impliableCutoff }
                    .mapNotNull { it[0]?.let(TransportFileName::parse) }
                val extended = TransportVector.extend(vector, impliable)
                for ((device, seq) in extended) {
                    if (vector[device] == seq) continue
                    check(TransportFileName.isSyncId(device)) { "Malformed device in ks_vector" }
                    connection.exec(
                        "INSERT INTO main.ks_vector (device, seq) VALUES ('$device', $seq) " +
                            "ON CONFLICT(device) DO UPDATE SET seq = MAX(seq, excluded.seq)",
                    )
                }
                val groups = covered.groupBy { it.device to it.number }
                for (name in covered) {
                    val implied = name.number <= (extended[name.device] ?: 0L)
                    val duplicated = groups.getValue(name.device to name.number).size > 1
                    // A parsed name: lowercase hex, digits, '-' and '.' only.
                    if (implied && !duplicated) connection.exec("DELETE FROM main.ks_covered WHERE name = '${name.fileName}'")
                }
                DeltaExportSql.platformCleanupStatements.forEach(connection::exec)
            }
        }
    }

    /**
     * The coverage and creation time of the validated transport file at [path], named [name]. Read-only.
     */
    fun readCoverage(path: String, name: TransportFileName): BaseCoverage =
        TransportConnections.open(path, TransportOpenMode.READ_ONLY).use { connection ->
            val createdAt = connection.queryRows("SELECT value FROM ks_meta WHERE key = '${TransportSchema.MetaKeys.CREATED_AT}'")
                .firstOrNull()?.firstOrNull()?.toLongOrNull() ?: 0L
            val vector = connection.queryRows("SELECT device, seq FROM ks_vector")
                .associate { it[0].orEmpty() to (it[1]?.toLongOrNull() ?: 0L) }
            val covered = connection.queryRows("SELECT name, created_at FROM ks_covered")
                .mapNotNull { row -> row[0]?.let { it to row[1]?.toLongOrNull() } }.toMap()
            BaseCoverage(name.fileName, vector, covered, createdAt)
        }

    /**
     * Brings the transport file at [path] to [TransportSchema.VERSION] before [DatabaseMerger.mergeTransport]
     * attaches it: a newer file throws [SchemaVersionException], an older one is migrated in place
     * ([TransportSchema.migrationPath]; the platform's SQLite exception propagates on failure, and an
     * older version with no migration path is an [IllegalStateException] — unclassified, so never
     * answered with a reset). Touches only that file.
     */
    fun prepareForMerge(path: String) {
        val version = TransportConnections.open(path, TransportOpenMode.READ_ONLY).use { it.userVersion() }
        if (version > TransportSchema.VERSION) {
            throw SchemaVersionException(localVersion = TransportSchema.VERSION, cloudVersion = version)
        }
        if (version == TransportSchema.VERSION) return
        val migrations = checkNotNull(TransportSchema.migrationPath(version)) { "No transport migration from version $version" }
        TransportConnections.open(path, TransportOpenMode.READ_WRITE).use { connection ->
            connection.transaction {
                for (migration in migrations) {
                    migration.statements.forEach(connection::exec)
                    connection.exec("PRAGMA user_version = ${migration.to}")
                }
            }
        }
    }

    /**
     * Whether the transport file at [path] has exactly the tables' columns of [TransportSchema.VERSION]
     * (every expected column, none beyond them — validation's check 5 for a transport file) —
     * [DatabaseMerger.mergeTransport]'s schema check for the ambiguous `SQLITE_ERROR` case. `null`
     * when the inspection itself fails (undetermined, never a reset).
     */
    fun schemaMatches(path: String): Boolean? = try {
        val expected = TransportSchema.EXPECTED_TABLES.getValue(TransportSchema.VERSION)
        TransportConnections.open(path, TransportOpenMode.READ_ONLY).use { connection ->
            SyncValidationSql.columnsProblem(expected, connection.columnsOf(expected), rejectExtra = true) == null
        }
    } catch (_: Exception) {
        null
    }

    private fun TransportConnection.userVersion(): Long =
        queryRows("PRAGMA user_version").firstOrNull()?.firstOrNull()?.toLongOrNull() ?: 0L

    private fun TransportConnection.columnsOf(tables: List<TableSpec>): Map<String, Set<String>> =
        tables.associate { table ->
            table.name to queryRows(SyncValidationSql.tableInfoQuery(table.name)).mapNotNull { it.getOrNull(1) }.toSet()
        }
}
