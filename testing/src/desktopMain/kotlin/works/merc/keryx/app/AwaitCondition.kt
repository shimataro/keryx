package works.merc.keryx.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** How long [awaitCondition] polls before failing the test. */
const val AWAIT_CONDITION_TIMEOUT_MS = 5_000L

/** How often [awaitCondition] re-evaluates its condition. */
private const val AWAIT_CONDITION_POLL_MS = 5L

/**
 * The thread [awaitCondition] polls on. Its own rather than `Dispatchers.Default`, which the whole
 * test JVM shares: work an earlier test left running there would otherwise stall the polling — and
 * the timeout too, which could then not even report. It only ever reads a condition, so one thread
 * serves every caller.
 */
private val awaitConditionDispatcher by lazy { testThreadDispatcher("await-condition") }

/**
 * Polls [condition] in real wall-clock time until it holds, failing with a timeout after
 * [timeoutMs] — for work that hops onto a real dispatcher (an HTTP engine's threads,
 * `Dispatchers.Default`) a virtual-time scheduler cannot advance.
 *
 * Runs on a thread of its own ([awaitConditionDispatcher]), so it polls in real time even when
 * called from inside `runTest`, whose own `delay` and `withTimeout` would otherwise be virtual.
 */
suspend fun awaitCondition(timeoutMs: Long = AWAIT_CONDITION_TIMEOUT_MS, condition: () -> Boolean) {
    withContext(awaitConditionDispatcher) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(AWAIT_CONDITION_POLL_MS)
        }
    }
}

/** [awaitCondition] for a caller that is not itself suspending. */
fun awaitConditionBlocking(timeoutMs: Long = AWAIT_CONDITION_TIMEOUT_MS, condition: () -> Boolean) =
    runBlocking { awaitCondition(timeoutMs, condition) }
