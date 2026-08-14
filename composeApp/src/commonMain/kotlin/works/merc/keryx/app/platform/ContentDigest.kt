package works.merc.keryx.app.platform

/**
 * Content hashing for the sync upload's "has anything actually changed?" check.
 *
 * `SyncRepository` compares the digest of the snapshot it is about to upload against the digest of
 * the one it uploaded last (`sync_state.last_uploaded_snapshot_digest`) and skips the upload when
 * they match. Comparing the bytes themselves is what makes that safe: the check can never report
 * "unchanged" for content that did change, so a skipped upload can never drop a local edit. The
 * opposite misjudgement — reporting a change that isn't one — merely uploads, exactly as before.
 *
 * This is a change detector, not a security primitive: nothing here authenticates the payload.
 */
expect object ContentDigest {
    /**
     * Hex-encoded SHA-256 of the file at [path], read incrementally.
     *
     * Takes a path rather than bytes because the snapshot it hashes is deliberately never held in
     * memory — the sync flow streams it straight from disk to the cloud. Returns `null` when the
     * file cannot be read, which callers treat as "no usable digest" and therefore upload.
     */
    fun sha256File(path: String): String?
}
