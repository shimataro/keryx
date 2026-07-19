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
     * [mergeStatements] in a transaction, then detaches. Throws on SQL error.
     */
    fun merge(
        localDbPath: String,
        cloudDbPath: String,
        localSchemaVersion: Long,
        mergeStatements: List<String>,
    )
}
