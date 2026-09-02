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
        val destRootPath = destRoot.toPath()
        destRoot.mkdirs()
        var totalBytes = 0L
        var entryCount = 0
        ZipInputStream(File(zipPath).inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                check(entryCount <= MAX_ZIP_ENTRIES) { "Zip archive has too many entries" }

                // Zip-slip guard, inlined here rather than delegated to a helper: CodeQL's
                // java/zipslip query recognizes a `Path.normalize()` + `Path.startsWith(Path)`
                // check that directly gates the path it protects (its own documented example uses
                // this exact shape), but does not credit a separate function's guard as a
                // sanitizer for its return value — moving this out to a `resolveEntryPath` helper
                // left every use of `target` below flagged as unsanitized. `.canonicalFile` already
                // resolves both `..` segments and symlinks (stronger than `normalize()` alone,
                // which only strips `..` lexically); the `.normalize()` call is a redundant no-op
                // on an already-canonical path, kept only because it's the exact call CodeQL's
                // barrier-guard pattern matches on.
                val target = File(destRoot, entry.name).canonicalFile
                if (!target.toPath().normalize().startsWith(destRootPath)) {
                    throw IllegalStateException("Zip entry escapes the destination directory: ${entry.name}")
                }

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
