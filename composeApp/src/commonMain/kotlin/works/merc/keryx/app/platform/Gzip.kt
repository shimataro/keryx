package works.merc.keryx.app.platform

/**
 * Gzip compression for the sync upload payload.
 *
 * `SyncRepository` compresses the upload snapshot before sending it, and decompresses a downloaded
 * cloud file before handing it to the merger. Both directions stream file-to-file, matching the
 * "never hold the sync DB in memory" discipline the rest of the transfer path follows (see
 * `data/cloud/CloudFileTransfer.kt`).
 */
expect object Gzip {
    /**
     * Compresses the file at [sourcePath] into a new gzip file at [destPath], replacing it if
     * present. Throws on I/O error.
     */
    fun compressFile(sourcePath: String, destPath: String)

    /**
     * Decompresses the gzip file at [sourcePath] into [destPath], replacing it if present.
     *
     * Throws when [sourcePath] is not a valid gzip stream (a foreign/corrupt cloud file) or on
     * I/O error — callers are expected to classify that as the cloud data being unusable, the same
     * way an invalid SQLite header is classified elsewhere in the sync flow.
     */
    fun decompressFile(sourcePath: String, destPath: String)
}
