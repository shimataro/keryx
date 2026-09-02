package works.merc.keryx.app.platform

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipInputStream

/** Bytes moved per read while extracting a single entry — matches the chunk size the rest of the
 * update pipeline (`data/remote/UpdateDownloader.kt`, `data/cloud/CloudFileTransfer.kt`) already
 * uses. */
private const val EXTRACT_CHUNK_BYTES = 64 * 1024

/** A defense against a maliciously (or corruptly) crafted archive with an unreasonable number of
 * entries — an update ZIP has at most a few thousand files. */
internal const val MAX_ZIP_ENTRIES = 100_000

actual object ZipExtractor {
    actual fun extract(zipPath: String, destDir: String, maxBytes: Long, executableEntries: Set<String>) {
        val destRoot = File(destDir).canonicalFile
        val destRootPath = destRoot.toPath()
        destRoot.mkdirs()
        var totalBytes = 0L
        var entryCount = 0
        // One buffer per call, not per entry: an app image is a few hundred entries, and at
        // MAX_ZIP_ENTRIES it would be a hundred thousand short-lived 64 KB allocations.
        val buffer = ByteArray(EXTRACT_CHUNK_BYTES)
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
                    totalBytes = copyEntry(zip, target.outputStream().buffered(), buffer, totalBytes, maxBytes)
                    if (entry.name in executableEntries) target.setExecutable(true)
                }
                zip.closeEntry()
            }
        }
    }

    actual fun validate(zipPath: String, destDir: String, maxBytes: Long) {
        val destRoot = File(destDir).canonicalFile
        val destRootPath = destRoot.toPath()
        var totalBytes = 0L
        var entryCount = 0
        val buffer = ByteArray(EXTRACT_CHUNK_BYTES)
        // Deliberately does not create destRoot: a caller that rejects the archive must be left
        // with nothing on disk at all.
        ZipInputStream(File(zipPath).inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                check(entryCount <= MAX_ZIP_ENTRIES) { "Zip archive has too many entries" }

                // A second copy of extract()'s guard rather than one helper both call: CodeQL only
                // credits the check where it directly gates the path it protects, and hoisting it
                // out left every write in extract() flagged as unsanitized (see the long comment
                // there). Keeping the two identical in shape is what makes them equivalent.
                val target = File(destRoot, entry.name).canonicalFile
                if (!target.toPath().normalize().startsWith(destRootPath)) {
                    throw IllegalStateException("Zip entry escapes the destination directory: ${entry.name}")
                }

                if (!entry.isDirectory) totalBytes = copyEntry(zip, OutputStream.nullOutputStream(), buffer, totalBytes, maxBytes)
                zip.closeEntry()
            }
        }
    }

    /**
     * Copies [zip]'s current entry into [out] (closing it), enforcing [maxBytes] against the running
     * [totalBytesSoFar] as it goes.
     *
     * Shared by [extract] and [validate] rather than duplicated so the byte accounting — and its
     * message — can only be defined once: a limit maintained in two places drifts, and the two paths
     * would then disagree about which archives are acceptable. [validate] passes
     * [OutputStream.nullOutputStream], which is what makes it a check rather than an extraction; a
     * local header's declared size can be absent (`-1`), so inflating is the only always-correct way
     * to know an archive's true uncompressed size.
     */
    private fun copyEntry(zip: ZipInputStream, out: OutputStream, buffer: ByteArray, totalBytesSoFar: Long, maxBytes: Long): Long {
        var total = totalBytesSoFar
        out.use { sink ->
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                total += read
                check(total <= maxBytes) { "Zip archive exceeds the $maxBytes-byte limit" }
                sink.write(buffer, 0, read)
            }
        }
        return total
    }
}
