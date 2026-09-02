package works.merc.keryx.app.platform.update

import works.merc.keryx.app.core.APP_NAME
import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.domain.InstallLaunchResult
import works.merc.keryx.app.domain.UpdateAssetKind
import works.merc.keryx.app.domain.UpdateInstaller
import works.merc.keryx.app.domain.UpdatePlan
import works.merc.keryx.app.platform.FileSystemExtras
import works.merc.keryx.app.platform.InstallKind
import works.merc.keryx.app.platform.InstallLocation
import works.merc.keryx.app.platform.ZipExtractor
import works.merc.keryx.app.platform.detectInstallLocation
import works.merc.keryx.app.platform.isWindows
import java.io.File
import java.io.IOException

/** [InstallKind]s [UpdatePlan.SelfReplace] can actually target — matches [updatePlan]'s own mapping. */
private val SELF_REPLACE_KINDS =
    setOf(InstallKind.MAC_APP_BUNDLE, InstallKind.WINDOWS_PORTABLE, InstallKind.LINUX_PORTABLE)

/** Uncompressed size an update ZIP is allowed to expand to, as a multiple of the compressed asset
 * size — generous headroom for a `.app`/app-image (mostly a JVM runtime, which compresses well)
 * without leaving [ZipExtractor]'s own `maxBytes` guard toothless. Also the free-space multiple
 * [selfReplace] checks both the cache volume (before extracting) and the install volume (before
 * staging) against — the extracted tree can never exceed this, since it's the same bound
 * [ZipExtractor] itself enforces while extracting. */
private const val ZIP_EXTRACT_MAX_BYTES_MULTIPLE = 10L

/**
 * Overflow-safe "is [usableBytes] enough for [baseBytes] `*` [multiple]" — mirrors
 * `domain.hasEnoughFreeSpaceForUpdate`'s own reasoning (see its KDoc for why a plain
 * `usableBytes < baseBytes * multiple` isn't used) but kept local here rather than shared with it:
 * [ZIP_EXTRACT_MAX_BYTES_MULTIPLE] is a desktop-only extraction-pipeline concept `domain/` — used
 * equally by Android, which never extracts anything locally — has no business knowing about.
 */
internal fun hasEnoughFreeSpace(usableBytes: Long, baseBytes: Long, multiple: Long): Boolean =
    baseBytes >= 0 && usableBytes / multiple >= baseBytes

/**
 * Desktop [UpdateInstaller]. For [UpdatePlan.SelfReplace] (macOS `.app`, Windows/Linux portable):
 * extracts the downloaded ZIP to a staging directory via [ZipExtractor], verifies it, moves it
 * next to the current install (so the swap the detached [UpdateScriptWriter] script performs is a
 * same-volume rename), and launches that script. For [UpdatePlan.RunInstaller] on an installed
 * Windows build: launches a script that waits for this process to exit, then runs `msiexec`
 * against the downloaded `.msi`.
 *
 * Either way this method only *launches* the hand-off and returns — returning
 * [InstallLaunchResult.Launched] is what makes `UpdateRepository` emit its `installLaunched`
 * signal, which `main.kt` collects to exit the whole app, and the launched script is what actually
 * waits for that exit before touching any files. (The app must never exit merely because state
 * reached `UpdateState.Installing`: that happens before this method has extracted anything.)
 *
 * Deliberately extracts every self-replace ZIP with the same [ZipExtractor] used for its own unit
 * tests, rather than shelling out to `ditto` on macOS for symlink/mode fidelity: the only symlinks
 * a jpackage macOS bundle contains live under its runtime's legal-notices directory (license text,
 * verified during PR2's `zip -y` fix), never in a path this app executes, so extracting them as
 * flattened regular files changes nothing the app depends on while keeping extraction on one
 * already-tested, in-process code path instead of adding a second synchronous external-process
 * dependency for a cosmetic difference.
 */
class DesktopUpdateInstaller internal constructor(
    private val location: InstallLocation,
    private val launcher: ProcessLauncher,
) : UpdateInstaller {

    constructor(location: InstallLocation = detectInstallLocation()) : this(location, RealProcessLauncher())

    override fun canInstall(plan: UpdatePlan): Boolean = when (plan) {
        is UpdatePlan.SelfReplace ->
            location.kind in SELF_REPLACE_KINDS && location.appRoot != null && location.launcherPath != null &&
                location.parentWritable && !location.translocated
        is UpdatePlan.RunInstaller ->
            plan.asset.kind == UpdateAssetKind.WINDOWS_MSI &&
                location.kind == InstallKind.WINDOWS_INSTALLED && location.launcherPath != null
        UpdatePlan.NotOffered, UpdatePlan.OpenReleasePage -> false
    }

    override suspend fun install(filePath: String, update: AvailableUpdate): InstallLaunchResult {
        val asset = update.asset ?: return InstallLaunchResult.Failed("No update asset was selected for this install")
        return when (update.plan) {
            is UpdatePlan.RunInstaller ->
                if (asset.kind == UpdateAssetKind.WINDOWS_MSI) {
                    installMsi(filePath, location.launcherPath)
                } else {
                    InstallLaunchResult.Failed("Unsupported installer asset kind: ${asset.kind}")
                }
            is UpdatePlan.SelfReplace -> {
                val appRoot = location.appRoot
                if (appRoot == null) {
                    InstallLaunchResult.Failed("No install directory to replace")
                } else {
                    selfReplace(filePath, appRoot, asset.kind, update.version)
                }
            }
            UpdatePlan.NotOffered, UpdatePlan.OpenReleasePage ->
                InstallLaunchResult.Failed("This install form has no in-app installer")
        }
    }

    /**
     * Extracts [filePath] (already downloaded and digest-verified) next to [appRoot], verifies the
     * result, stages it as a same-volume sibling of [appRoot], and launches the detached
     * self-replace script that swaps it in.
     */
    private fun selfReplace(filePath: String, appRoot: String, assetKind: UpdateAssetKind, expectedVersion: String): InstallLaunchResult {
        val appRootFile = File(appRoot)
        val parent = appRootFile.parentFile
            ?: return InstallLaunchResult.Failed("Install directory has no parent")
        val workDir = File(filePath).parentFile
            ?: return InstallLaunchResult.Failed("Downloaded file has no parent directory")

        val entryDirName = when (assetKind) {
            UpdateAssetKind.MAC_APP_ZIP -> "$APP_NAME.app"
            UpdateAssetKind.WINDOWS_ZIP, UpdateAssetKind.LINUX_ZIP -> APP_NAME
            else -> return InstallLaunchResult.Failed("Unsupported self-replace asset kind: $assetKind")
        }
        val executableEntries = when (assetKind) {
            UpdateAssetKind.MAC_APP_ZIP -> setOf(
                "$entryDirName/Contents/MacOS/$APP_NAME",
                "$entryDirName/Contents/runtime/Contents/Home/lib/jspawnhelper",
            )
            UpdateAssetKind.LINUX_ZIP -> setOf("$entryDirName/bin/$APP_NAME")
            else -> emptySet()
        }

        val zipSize = File(filePath).length()
        // The 3x check runDownload() already did covers the download itself (the .part file
        // briefly coexisting with its verified rename) — extraction needs its own, much larger
        // headroom on the very same volume (the ZIP's parent dir, under <cacheDir>/updates/), so
        // this re-checks with the multiple that actually matters here before doing the expensive
        // part.
        if (!hasEnoughFreeSpace(FileSystemExtras.usableSpaceBytes(workDir.path), zipSize, ZIP_EXTRACT_MAX_BYTES_MULTIPLE)) {
            return InstallLaunchResult.Failed("Not enough free disk space to extract the downloaded update")
        }

        val extractDir = File(workDir, "extracted")
        try {
            ZipExtractor.extract(
                zipPath = filePath,
                destDir = extractDir.path,
                maxBytes = zipSize * ZIP_EXTRACT_MAX_BYTES_MULTIPLE,
                executableEntries = executableEntries,
            )
        } catch (e: IllegalStateException) {
            return InstallLaunchResult.Failed("Failed to extract the downloaded update: ${e.message}")
        } catch (e: IOException) {
            // Kotlin has no multi-catch, so this is a second clause rather than widening the one
            // above to Exception (which would also swallow CancellationException on this suspend
            // path). Covers a full disk (ENOSPC) or a corrupt archive (ZipException) — anything
            // ZipExtractor's own java.util.zip/java.io calls can throw beyond its check()s.
            return InstallLaunchResult.Failed("Failed to extract the downloaded update: ${e.message}")
        }

        val extractedApp = File(extractDir, entryDirName)
        verifyExtractedApp(extractedApp, assetKind, expectedVersion)?.let { reason ->
            FileSystemExtras.deleteRecursively(extractDir.path)
            return InstallLaunchResult.Failed(reason)
        }

        // appRoot's own volume — not necessarily the same one workDir/extractDir are on — needs its
        // own equivalent check before staging: ZipExtractor's own maxBytes guard already bounds the
        // extracted tree at zipSize * ZIP_EXTRACT_MAX_BYTES_MULTIPLE, so that same figure is a valid
        // upper bound on what's about to be moved (or, cross-volume, copied) onto it.
        if (!hasEnoughFreeSpace(FileSystemExtras.usableSpaceBytes(parent.path), zipSize, ZIP_EXTRACT_MAX_BYTES_MULTIPLE)) {
            FileSystemExtras.deleteRecursively(extractDir.path)
            return InstallLaunchResult.Failed("Not enough free disk space on the install volume to stage the update")
        }

        val newDir = File(parent, ".${appRootFile.name}.new")
        FileSystemExtras.deleteRecursively(newDir.path) // clear any leftover from a previously failed attempt
        if (!FileSystemExtras.move(extractedApp.path, newDir.path)) {
            FileSystemExtras.deleteRecursively(extractDir.path)
            return InstallLaunchResult.Failed("Could not stage the extracted update next to the current install")
        }

        // The extracted tree just moved out from under extractDir, and the downloaded ZIP itself is
        // no longer needed once staged — clean up both immediately rather than leaving them for the
        // next check()'s sweep, which could be a long time away (or never come, on a Failed retry
        // loop — see UpdateRepository.retryFailed).
        FileSystemExtras.deleteRecursively(extractDir.path)
        File(filePath).delete()

        val oldDir = File(parent, ".${appRootFile.name}.old")
        val logFile = File(workDir, "apply.log")
        val pid = ProcessHandle.current().pid().toString()

        return when (assetKind) {
            UpdateAssetKind.MAC_APP_ZIP -> launchScript(
                scriptFile = File(workDir, "apply.sh"),
                scriptText = UpdateScriptWriter.macSelfReplace(),
                command = listOf(
                    "/bin/sh", File(workDir, "apply.sh").path, pid,
                    appRootFile.path, newDir.path, oldDir.path, logFile.path,
                ),
                executableScript = true,
            )
            UpdateAssetKind.LINUX_ZIP -> launchScript(
                scriptFile = File(workDir, "apply.sh"),
                scriptText = UpdateScriptWriter.linuxSelfReplace(),
                command = listOf(
                    "/bin/sh", File(workDir, "apply.sh").path, pid,
                    appRootFile.path, newDir.path, oldDir.path, logFile.path,
                ),
                executableScript = true,
            )
            UpdateAssetKind.WINDOWS_ZIP -> launchScript(
                scriptFile = File(workDir, "apply.cmd"),
                scriptText = UpdateScriptWriter.windowsSelfReplace(),
                command = listOf(
                    "cmd", "/c", File(workDir, "apply.cmd").path, pid,
                    appRootFile.path, newDir.path, oldDir.path,
                ),
                executableScript = false,
            )
        }
    }

    /** Launches the detached `msiexec` hand-off script for an installed Windows build. */
    private fun installMsi(filePath: String, launcherPath: String?): InstallLaunchResult {
        if (launcherPath == null) return InstallLaunchResult.Failed("Current launcher path is unknown")
        val workDir = File(filePath).parentFile
            ?: return InstallLaunchResult.Failed("Downloaded file has no parent directory")
        val scriptFile = File(workDir, "apply.cmd")
        val logFile = File(workDir, "apply.log")
        val pid = ProcessHandle.current().pid().toString()
        return launchScript(
            scriptFile = scriptFile,
            scriptText = UpdateScriptWriter.windowsMsiInstall(),
            command = listOf("cmd", "/c", scriptFile.path, pid, filePath, launcherPath, logFile.path),
            executableScript = false,
        )
    }

    /** Writes [scriptText] to [scriptFile] (marking it executable on a POSIX platform), then hands
     * [command] off to [launcher]. */
    private fun launchScript(scriptFile: File, scriptText: String, command: List<String>, executableScript: Boolean): InstallLaunchResult =
        try {
            scriptFile.writeText(scriptText)
            if (executableScript && !isWindows) FileSystemExtras.setExecutable(scriptFile.path)
            if (launcher.launch(command)) {
                InstallLaunchResult.Launched
            } else {
                InstallLaunchResult.Failed("Could not launch the update helper script")
            }
        } catch (e: IOException) {
            InstallLaunchResult.Failed("Could not write the update helper script: ${e.message}")
        }

    /** Health-checks a freshly extracted update before it's staged/swapped in — a failure here
     * means the download or archive was bad in some way [UpdateDownloader]'s digest check didn't
     * catch (e.g. an unexpected internal layout), and must be caught before any file that belongs
     * to the current, working install is touched. */
    private fun verifyExtractedApp(extractedApp: File, assetKind: UpdateAssetKind, expectedVersion: String): String? {
        if (!extractedApp.isDirectory) return "Extracted update is missing its app directory"
        return when (assetKind) {
            UpdateAssetKind.MAC_APP_ZIP -> {
                val exe = File(extractedApp, "Contents/MacOS/$APP_NAME")
                val plistVersion = readPlistStringValue(File(extractedApp, "Contents/Info.plist").path, "CFBundleShortVersionString")
                when {
                    !exe.canExecute() -> "Extracted app's launcher isn't executable"
                    plistVersion != expectedVersion -> "Extracted app reports version $plistVersion, expected $expectedVersion"
                    else -> null
                }
            }
            UpdateAssetKind.LINUX_ZIP -> {
                val exe = File(extractedApp, "bin/$APP_NAME")
                if (!exe.canExecute()) "Extracted app's launcher isn't executable" else null
            }
            UpdateAssetKind.WINDOWS_ZIP -> {
                val exe = File(extractedApp, "$APP_NAME.exe")
                if (!exe.isFile) "Extracted app is missing $APP_NAME.exe" else null
            }
            else -> "Unsupported self-replace asset kind: $assetKind"
        }
    }
}

/** Reads a top-level `<key>[key]</key><string>...</string>` value out of a plist file by plain
 * text search rather than a full XML parse — jpackage's generated Info.plist is a small, fixed,
 * non-attacker-controlled file (it comes from inside an already digest-verified ZIP), so this
 * avoids pulling in a plist/XML dependency for a single field. */
private fun readPlistStringValue(plistPath: String, key: String): String? {
    val file = File(plistPath)
    if (!file.isFile) return null
    val text = file.readText()
    val keyIndex = text.indexOf("<key>$key</key>")
    if (keyIndex < 0) return null
    val stringStart = text.indexOf("<string>", keyIndex)
    if (stringStart < 0) return null
    val valueStart = stringStart + "<string>".length
    val valueEnd = text.indexOf("</string>", valueStart)
    if (valueEnd < 0) return null
    return text.substring(valueStart, valueEnd)
}
