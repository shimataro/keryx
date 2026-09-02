package works.merc.keryx.app.platform.update

import works.merc.keryx.app.core.Log
import java.io.IOException

private const val TAG = "DetachedProcess"

/**
 * Seam over launching the detached helper script that outlives this process (an
 * [UpdateScriptWriter] script, or `msiexec` directly) — see that file's own KDoc for why the
 * script waits out this process's PID before touching anything. Faking this in tests is the whole
 * point: [DesktopUpdateInstallerTest] asserts the exact command line an install decision would run,
 * without ever actually spawning a self-replace or an installer.
 */
internal fun interface ProcessLauncher {
    /**
     * Starts [command] detached from this process and returns whether the launch itself succeeded
     * (the OS accepted the exec) — it says nothing about whether the script later succeeds, since
     * by design nothing here waits for it to finish; the caller exits shortly after this returns
     * `true` and the script's own log file is the only record of what happened next.
     */
    fun launch(command: List<String>): Boolean
}

/**
 * Real launcher backed by [ProcessBuilder]. Output is discarded rather than inherited or piped:
 * the launched script redirects its own output to a log-file argument (see [UpdateScriptWriter]),
 * and inheriting this (about to exit) process's streams would keep the child attached to file
 * descriptors nobody reads from afterwards.
 */
internal class RealProcessLauncher : ProcessLauncher {
    override fun launch(command: List<String>): Boolean = try {
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectInput(ProcessBuilder.Redirect.DISCARD)
            .start()
        true
    } catch (e: IOException) {
        Log.error(TAG, "Failed to launch detached update process: ${command.firstOrNull()}", e)
        false
    }
}
