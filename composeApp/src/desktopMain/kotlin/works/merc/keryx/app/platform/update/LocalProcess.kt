package works.merc.keryx.app.platform.update

import works.merc.keryx.app.core.Log
import java.io.File
import java.util.concurrent.TimeUnit

/** How long to wait for a force-killed child to actually go away before giving up on it. Short by
 * design: this is only here so a caller that is about to touch the child's output directory isn't
 * racing a process that is still writing into it. */
private const val DESTROY_GRACE_SECONDS = 5L

/** What running a short-lived local helper process ended up doing. A type rather than an `Int` with
 * sentinel values because the outcome is the only trace an install failure leaves — reporting a
 * timeout as `exit -1` makes the log indistinguishable from a genuine non-zero exit. */
internal sealed interface LocalProcessResult {
    /** The process ran to completion and reported [code]. */
    data class Exited(val code: Int) : LocalProcessResult

    /** The process outlived its timeout and was force-killed. */
    data object TimedOut : LocalProcessResult

    /** The calling thread was interrupted while waiting; the child was force-killed. */
    data object Interrupted : LocalProcessResult
}

/**
 * Runs [command] to completion with both its streams discarded, bounded by [timeoutSeconds].
 *
 * Shared by every synchronous local-tool invocation in this package (`codesign`, `ditto`) so the
 * fiddly parts exist once: a child that outlives its timeout is force-killed **and then waited out**
 * — otherwise a `ditto` still unpacking into a staging directory would race the next attempt's
 * pre-clear — and an interruption restores the thread's interrupt flag rather than swallowing it.
 * Deliberately does *not* catch [java.io.IOException] from launching: whether an absent binary is a
 * failed verification or a failed install is the caller's decision, not this function's.
 *
 * @param tag the caller's log tag, so a timeout is attributed to the tool that hung.
 * @param errorLog where to send the child's stderr, for a caller that needs to report *why* the tool
 *   refused. A file rather than a pipe on purpose: a pipe that fills would block the child while
 *   this function is waiting on the timeout, deadlocking both. Discarded when `null`.
 */
internal fun runLocalProcess(command: List<String>, timeoutSeconds: Long, tag: String, errorLog: File? = null): LocalProcessResult {
    val process = ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(if (errorLog == null) ProcessBuilder.Redirect.DISCARD else ProcessBuilder.Redirect.to(errorLog))
        .start()
    return try {
        if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            LocalProcessResult.Exited(process.exitValue())
        } else {
            Log.error(tag, "${command.firstOrNull()} timed out after $timeoutSeconds s")
            LocalProcessResult.TimedOut
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        Log.error(tag, "Interrupted while waiting for ${command.firstOrNull()}", e)
        LocalProcessResult.Interrupted
    } finally {
        if (process.isAlive) {
            process.destroyForcibly()
            try {
                process.waitFor(DESTROY_GRACE_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
