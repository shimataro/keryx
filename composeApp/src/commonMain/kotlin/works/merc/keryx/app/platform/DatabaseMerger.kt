package works.merc.keryx.app.platform

/**
 * Performs the ATTACH-DATABASE merge on a single dedicated DB connection.
 *
 * This must NOT go through the app's SQLDelight driver: that driver opens a
 * fresh connection per statement for file databases, so an `ATTACH` would not
 * be visible to the subsequent merge statements. Doing the whole merge on one
 * connection (and committing) keeps the attachment alive; the SQLDelight driver
 * then reads the committed result on its next query.
 */
expect object DatabaseMerger {
    /**
     * Attaches [cloudDbPath] to the local DB at [localDbPath], verifies the
     * cloud schema is not newer than [localSchemaVersion] (throws
     * [works.merc.keryx.app.core.SchemaVersionException] if it is), runs
     * [mergeStatements] in a transaction, then detaches.
     *
     * A merge failure caused by a corrupt cloud DB or by cloud data this app's schema cannot
     * represent (a UNIQUE/NOT NULL/FOREIGN KEY violation, a foreign/legacy table layout) is
     * classified and rethrown as [works.merc.keryx.app.core.CloudDataIncompatibleException]. Any
     * other SQL error is rethrown unchanged.
     */
    fun merge(
        localDbPath: String,
        cloudDbPath: String,
        localSchemaVersion: Long,
        mergeStatements: List<String>,
    )

    /**
     * Validates that the database at [dbPath] carries the schema (tables and columns)
     * [works.merc.keryx.app.domain.MergeSql] needs for [schemaVersion].
     *
     * Must be updated when [works.merc.keryx.app.data.local.db.KeryxDatabase.Schema.version]
     * is bumped and [works.merc.keryx.app.domain.MergeSql] references new tables or columns.
     *
     * @return `true` if structurally compatible, `false` if definitely incompatible, `null` when
     * this build has no expectation registered for [schemaVersion] (undetermined — e.g. a schema
     * bump the expectation table forgot to cover). Callers must treat `null` the same as `true`:
     * an undetermined verdict must never be used to offer a destructive reset.
     */
    fun validateSchema(dbPath: String, schemaVersion: Long): Boolean?
}
