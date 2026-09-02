package works.merc.keryx.app.platform

import java.io.File
import java.util.zip.ZipInputStream

/** Bytes moved per read while extracting a single entry — matches the chunk size the rest of the
 * update pipeline (`data/remote/UpdateDownloader.kt`, `data/cloud/CloudFileTransfer.kt`) already
 * uses. */
private const val EXTRACT_CHUNK_BYTES = 64 * 1024

/** A defense against a maliciously (or corruptly) crafted archive with an unreasonable number of
 * entries — an update ZIP has at most a few thousand files. */
private const val MAX_ZIP_ENTRIES = 100_000

actual object ZipExtractor {
    actual fun extract(zipPath: String, destDir: String, maxBytes: Long, executableEntries: Set<String>) {
        val destRoot = File(destDir).canonicalFile
        destRoot.mkdirs()
        var totalBytes = 0L
        var entryCount = 0
        ZipInputStream(File(zipPath).inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                check(entryCount <= MAX_ZIP_ENTRIES) { "Zip archive has too many entries" }

                val target = resolveEntryPath(destRoot, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    totalBytes = writeEntry(zip, target, totalBytes, maxBytes)
                    if (entry.name in executableEntries) target.setExecutable(true)
                }
                zip.closeEntry()
            }
        }
    }

    /** Resolves [entryName] against [destRoot], rejecting a "zip slip" entry whose resolved path
     * (after normalizing `..`/symlinks via [File.getCanonicalFile]) would land outside [destRoot]. */
    private fun resolveEntryPath(destRoot: File, entryName: String): File {
        val candidate = File(destRoot, entryName).canonicalFile
        val withinRoot = candidate.path == destRoot.path || candidate.path.startsWith(destRoot.path + File.separator)
        check(withinRoot) { "Zip entry escapes the destination directory: $entryName" }
        return candidate
    }

    private fun writeEntry(zip: ZipInputStream, target: File, totalBytesSoFar: Long, maxBytes: Long): Long {
        var total = totalBytesSoFar
        target.outputStream().buffered().use { out ->
            val buffer = ByteArray(EXTRACT_CHUNK_BYTES)
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                total += read
                check(total <= maxBytes) { "Zip archive exceeds the $maxBytes-byte limit" }
                out.write(buffer, 0, read)
            }
        }
        return total
    }
}
