package works.merc.keryx.app.platform

import works.merc.keryx.app.core.Log
import java.io.File
import java.io.IOException

private const val TAG = "InstallLocation"

/**
 * The executable this process was launched from. jpackage's native launcher sets
 * `jpackage.app-path` to its own absolute path; that property is absent under `./gradlew run` or an
 * IDE launch, where [ProcessHandle.info]'s command (the `java`/`java.exe` binary itself) is the
 * fallback and is what marks the run as [InstallKind.DEVELOPMENT] below.
 */
private fun launcherPath(): String? =
    System.getProperty("jpackage.app-path")
        ?: ProcessHandle.current().info().command().orElse(null)

/** Probes actual writability by creating and deleting a temp file — Windows ACLs aren't reliably
 * reflected by [File.canWrite]. */
internal fun isWritable(dir: File): Boolean {
    if (!dir.isDirectory) return false
    return try {
        val probe = File.createTempFile("keryx-update-probe", ".tmp", dir)
        probe.delete()
        true
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

actual fun detectInstallLocation(): InstallLocation {
    val launcher = launcherPath()
    val launcherName = launcher?.let(::File)?.name?.lowercase()

    if (launcher == null || launcherName == "java" || launcherName == "java.exe") {
        Log.info(TAG, "No jpackage launcher detected (launcher=$launcher) — treating as a development run")
        return InstallLocation(InstallKind.DEVELOPMENT, null, launcher, parentWritable = false, translocated = false)
    }

    // launcher is smart-cast non-null past the guard above, so this File(...) is never null.
    val launcherFile = File(launcher)
    return when {
        isMacOs -> detectMacInstallLocation(launcher, launcherFile)
        isWindows -> detectWindowsInstallLocation(launcher, launcherFile)
        isLinux -> detectLinuxInstallLocation(launcher, launcherFile)
        else -> InstallLocation(InstallKind.UNKNOWN, null, launcher, parentWritable = false, translocated = false)
    }
}

/** `.../Keryx.app/Contents/MacOS/Keryx` → the bundle root is three levels up from the launcher. */
internal fun detectMacInstallLocation(launcher: String, launcherFile: File): InstallLocation {
    val macOsDir = launcherFile.parentFile // Contents/MacOS
    val contentsDir = macOsDir?.parentFile // Contents
    val appRoot = contentsDir?.parentFile // Keryx.app
    if (appRoot == null || contentsDir.name != "Contents" || appRoot.name?.endsWith(".app") != true) {
        Log.warn(TAG, "Launcher path didn't match the expected .app/Contents/MacOS layout: $launcher")
        return InstallLocation(InstallKind.UNKNOWN, null, launcher, parentWritable = false, translocated = false)
    }
    val translocated = appRoot.path.contains("/AppTranslocation/")
    val parentWritable = appRoot.parentFile?.let(::isWritable) ?: false
    return InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, launcher, parentWritable, translocated)
}

private val WINDOWS_SYSTEM_ROOTS = listOf("ProgramFiles", "ProgramFiles(x86)", "ProgramW6432")

/** `<install>\Keryx.exe` → the app directory is the launcher's parent. */
internal fun detectWindowsInstallLocation(launcher: String, launcherFile: File): InstallLocation {
    val appRoot = launcherFile.parentFile
        ?: return InstallLocation(InstallKind.UNKNOWN, null, launcher, parentWritable = false, translocated = false)
    // Self-replace renames the whole app directory within its parent (mirroring the macOS .app
    // bundle swap) rather than writing inside it, so it's the *parent's* write permission that
    // actually matters here — consistent with parentWritable's own name and detectMacInstallLocation.
    val parentWritable = appRoot.parentFile?.let(::isWritable) ?: false
    val underProgramFiles = WINDOWS_SYSTEM_ROOTS.any { env ->
        System.getenv(env)?.let { root -> appRoot.path.startsWith(root, ignoreCase = true) } ?: false
    }
    val kind = if (underProgramFiles || !parentWritable) InstallKind.WINDOWS_INSTALLED else InstallKind.WINDOWS_PORTABLE
    return InstallLocation(kind, appRoot.path, launcher, parentWritable, translocated = false)
}

private val LINUX_SYSTEM_ROOTS = listOf("/opt", "/usr", "/usr/local")

/** `<install>/bin/Keryx` → the app-image root is the launcher's grandparent. */
internal fun detectLinuxInstallLocation(launcher: String, launcherFile: File): InstallLocation {
    val binDir = launcherFile.parentFile
    val appRoot = binDir?.parentFile
    if (appRoot == null || binDir.name != "bin") {
        Log.warn(TAG, "Launcher path didn't match the expected <root>/bin/Keryx layout: $launcher")
        return InstallLocation(InstallKind.UNKNOWN, null, launcher, parentWritable = false, translocated = false)
    }
    val underSystemRoot = LINUX_SYSTEM_ROOTS.any { appRoot.path == it || appRoot.path.startsWith("$it/") }
    if (underSystemRoot) {
        return InstallLocation(InstallKind.LINUX_PACKAGE, appRoot.path, launcher, parentWritable = false, translocated = false)
    }
    // Same reasoning as the Windows case above: a rename-based self-replace needs the *parent*
    // directory to be writable, not appRoot itself.
    val parentWritable = appRoot.parentFile?.let(::isWritable) ?: false
    val kind = if (parentWritable) InstallKind.LINUX_PORTABLE else InstallKind.UNKNOWN
    return InstallLocation(kind, appRoot.path, launcher, parentWritable, translocated = false)
}
