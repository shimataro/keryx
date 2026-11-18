package works.merc.keryx.app

import java.io.IOException
import java.util.concurrent.TimeUnit

/** Runs [command], aborting and throwing [IOException] if it outlives [timeoutMillis]. Returns the exit code otherwise. */
internal fun runProcessWithTimeout(command: List<String>, timeoutMillis: Long): Int {
    val proc = ProcessBuilder(command).start()
    if (!proc.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
        proc.destroyForcibly()
        throw IOException("${command.first()} timed out")
    }
    return proc.exitValue()
}
