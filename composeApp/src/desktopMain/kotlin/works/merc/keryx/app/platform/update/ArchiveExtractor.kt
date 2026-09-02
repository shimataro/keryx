package works.merc.keryx.app.platform.update

import works.merc.keryx.app.platform.MAX_ZIP_ENTRIES
import works.merc.keryx.app.platform.ZipExtractor
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

private const val TAG = "ArchiveExtractor"

/**
 * Generous ceiling on how long unpacking an update archive may take. Extracting a real ~190MB macOS
 * bundle measures 1-2 seconds, so this only exists to stop a wedged child process from hanging the
 * install forever — the same "an update asset is large" reasoning behind
 * `UPDATE_DOWNLOAD_SOCKET_TIMEOUT_MS`, not a figure any healthy extraction approaches.
 */
private const val EXTRACT_TIMEOUT_SECONDS = 300L

/** How much of `ditto`'s own complaint to carry into the failure reason. Its messages are one short
 * line ("Not a directory"), and that reason is the only trace an install failure leaves. */
private const val MAX_EXTRACT_ERROR_CHARS = 200

/**
 * How [DesktopUpdateInstaller] unpacks a downloaded self-replace ZIP.
 *
 * A seam because the two implementations below are **not** interchangeable — one preserves symbolic
 * links and one cannot (see each) — so which is in use is a per-platform decision worth naming, and
 * because `DesktopUpdateInstallerTest` needs a way to fail an extraction without a real archive.
 */
internal fun interface ArchiveExtractor {
    /**
     * Extracts [zipPath] into [destDir], creating it if needed.
     *
     * @param maxBytes Upper bound on the archive's total uncompressed size.
     * @param executableEntries Archive-root-relative entry paths to mark executable. This is the
     *   information `java.util.zip` throws away rather than a general parameter, so only
     *   [InProcessArchiveExtractor] uses it — [DittoArchiveExtractor] restores every mode from
     *   the archive itself and ignores it.
     * @throws IllegalStateException on a rejected archive (zip slip, entry-count or size limit) or
     *   an extraction that failed outright.
     * @throws IOException on an I/O failure.
     */
    fun extract(zipPath: String, destDir: String, maxBytes: Long, executableEntries: Set<String>)
}

/**
 * Which [ArchiveExtractor] a real install uses.
 *
 * A separate function purely so both branches are assertable: this one choice is the entire delivery
 * mechanism for extracting a macOS bundle with `ditto`, and [DesktopUpdateInstaller]'s own tests all
 * either inject an extractor or take the in-process default, so nothing else would pin it.
 *
 * Keyed on the host OS rather than the asset kind because the two are equivalent here —
 * `detectInstallLocation` reports `MAC_APP_BUNDLE` only on macOS and never anywhere else, so
 * `SELF_REPLACE_KINDS` maps one-to-one onto the platform — and the OS is known before an asset is.
 */
internal fun defaultArchiveExtractor(isMac: Boolean): ArchiveExtractor =
    if (isMac) DittoArchiveExtractor() else InProcessArchiveExtractor

/**
 * In-process extraction via [ZipExtractor]. Used on Windows and Linux, whose app images carry no
 * code signature for a flattened symlink to invalidate, and as the test-facing default in
 * [DesktopUpdateInstaller]'s own constructor.
 */
internal object InProcessArchiveExtractor : ArchiveExtractor {
    override fun extract(zipPath: String, destDir: String, maxBytes: Long, executableEntries: Set<String>) =
        ZipExtractor.extract(zipPath, destDir, maxBytes, executableEntries)
}

/**
 * Extraction via `ditto -x -k`, used for a macOS `.app`.
 *
 * `ditto` is the only option here, not a preference: a signed bundle's `CodeResources` seals the 43
 * symbolic links in its bundled JDK (`Contents/runtime/.../legal/`) *as links*, and `java.util.zip`
 * cannot even identify a symlink entry — it writes each one out as a regular file holding the link
 * target, which makes the extracted bundle fail
 * [DesktopUpdateInstaller]'s own `codesign --verify --strict --deep` check every single time. Apple's
 * own tool restores links and modes exactly as stored.
 *
 * [ZipExtractor.validate] runs first because `ditto` enforces no limits of its own; that keeps this
 * path's zip-slip, entry-count and uncompressed-size guards identical to the in-process one's. What
 * it cannot check is a stored link's *target* (again: `java.util.zip` can't see which entries are
 * links). `ditto` itself is what covers that, on an archive already SHA-256-verified against the
 * GitHub release: it normalizes a `..` entry name into the destination rather than escaping it, and
 * refuses to write *through* a symlink at all, exiting non-zero. [DesktopUpdateInstaller]'s
 * code-signature check is deliberately *not* counted as a second line of defense for an escape: it
 * is a self-consistency check over the bundle directory only, so an entry written beside the bundle
 * is never inspected, and an attacker able to produce the whole archive can ad-hoc sign their own
 * bundle. It catches modification *inside* the bundle — which is what it is there for.
 */
internal class DittoArchiveExtractor : ArchiveExtractor {
    override fun extract(zipPath: String, destDir: String, maxBytes: Long, executableEntries: Set<String>) {
        ZipExtractor.validate(zipPath, destDir, maxBytes)
        val errorLog = File.createTempFile("keryx-ditto", ".log")
        try {
            // Absolute path, not a PATH lookup: this runs the moment the user consents to an
            // install, so a planted `ditto` earlier on an inherited PATH would run with the app's
            // privileges.
            val command = listOf("/usr/bin/ditto", "-x", "-k", zipPath, destDir)
            when (val result = runLocalProcess(command, EXTRACT_TIMEOUT_SECONDS, TAG, errorLog)) {
                is LocalProcessResult.Exited ->
                    check(result.code == 0) {
                        "ditto could not extract the update archive (exit ${result.code})${errorDetail(errorLog)}"
                    }
                LocalProcessResult.TimedOut ->
                    error("ditto timed out after $EXTRACT_TIMEOUT_SECONDS seconds extracting the update archive")
                LocalProcessResult.Interrupted ->
                    error("Interrupted while extracting the update archive")
            }
        } finally {
            errorLog.delete()
        }
        verifyExtractedTree(destDir, maxBytes)
    }

    /** `ditto`'s own stderr, flattened to one bounded line, or empty when it said nothing. Without
     * this the layer that actually stops a symlink escape — ditto refusing to write through one —
     * leaves no trace when it fires, and reads exactly like a corrupt archive. */
    private fun errorDetail(errorLog: File): String {
        val text = try {
            errorLog.readText()
        } catch (_: IOException) {
            return ""
        }
        val flattened = text.lineSequence().joinToString(" ") { it.trim() }.trim().filterNot { it.isISOControl() }
        return if (flattened.isEmpty()) "" else ": " + flattened.take(MAX_EXTRACT_ERROR_CHARS)
    }

    /**
     * Checks the tree `ditto` actually produced, not the archive that described it.
     *
     * [ZipExtractor.validate] inspects the *input*, and check and write now live in two processes
     * with two different ZIP readers: `ZipInputStream` walks local file headers and never consults
     * the central directory, while `ditto` treats the central directory as authoritative. An archive
     * whose two views disagree passes `validate` on the harmless one and is extracted by `ditto` on
     * the other. `validate` also cannot resolve a symlink target at all. Walking the result closes
     * both gaps against the one thing that matters: nothing may end up outside [destDir].
     */
    private fun verifyExtractedTree(destDir: String, maxBytes: Long) {
        val root = File(destDir).canonicalFile.toPath()
        var entries = 0
        var bytes = 0L
        Files.walkFileTree( // does not follow links: a symlinked directory is visited as a link
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    entries++
                    check(entries <= MAX_ZIP_ENTRIES) { "Extracted update has too many entries" }
                    if (attrs.isSymbolicLink) {
                        val target = Files.readSymbolicLink(file)
                        val resolved = (if (target.isAbsolute) target else file.parent.resolve(target)).normalize()
                        check(resolved.startsWith(root)) {
                            "Extracted update contains a symlink pointing outside it: ${root.relativize(file)}"
                        }
                    } else {
                        bytes += attrs.size()
                        check(bytes <= maxBytes) { "Extracted update exceeds the $maxBytes-byte limit" }
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
