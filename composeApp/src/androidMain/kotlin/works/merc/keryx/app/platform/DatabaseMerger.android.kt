package works.merc.keryx.app.platform

/**
 * Stub — cloud sync is not implemented on Android yet (see the plan's Phase 4). Unreachable in
 * this phase: `platformModule`'s Android `CloudSession` is constructed with no providers, so
 * `SyncRepository.sync()`'s `cloudProvider() ?: return` guard short-circuits before ever calling
 * [merge] or `DatabaseSnapshot.exportForUpload`. Throws with a clear message instead of silently
 * no-op-ing, so a future caller that reaches this before Phase 4 lands fails loudly rather than
 * pretending to have merged.
 */
actual object DatabaseMerger {
    actual fun merge(
        localDbPath: String,
        cloudDbPath: String,
        localSchemaVersion: Long,
        mergeStatements: List<String>,
    ): Unit = notImplemented()

    actual fun validateSchema(dbPath: String, schemaVersion: Long): Boolean? = notImplemented()

    private fun notImplemented(): Nothing =
        error("DatabaseMerger is not implemented on Android yet (cloud sync is Phase 4) — this should be unreachable while CloudSession has no providers")
}
