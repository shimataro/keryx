package works.merc.keryx.app.platform

/**
 * Stub — see [DatabaseMerger]'s KDoc: cloud sync is Phase 4 work, and unreachable in this phase
 * because `platformModule`'s Android `CloudSession` has no providers.
 */
actual object DatabaseSnapshot {
    actual fun exportForUpload(localDbPath: String, destPath: String): Unit =
        error("DatabaseSnapshot is not implemented on Android yet (cloud sync is Phase 4) — this should be unreachable while CloudSession has no providers")
}
