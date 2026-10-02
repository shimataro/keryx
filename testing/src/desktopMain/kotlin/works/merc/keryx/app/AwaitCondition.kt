package works.merc.keryx.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** How long [awaitCondition] polls before failing the test. */
const val AWAIT_CONDITION_TIMEOUT_MS = 5_000L

/** How often [awaitCondition] re-evaluates its condition. */
private const val AWAIT_CONDITION_POLL_MS = 5L

/**
 * Polls [condition] in real wall-clock time until it holds, failing with a timeout after
 * [timeoutMs] — for work that hops onto a real dispatcher (an HTTP engine's threads,
 * `Dispatchers.Default`) a virtual-time scheduler cannot advance.
 *
 * Runs on [Dispatchers.Default], so it polls in real time even when called from inside `runTest`,
 * whose own `delay` and `withTimeout` would otherwise be virtual.
 */
suspend fun awaitCondition(timeoutMs: Long = AWAIT_CONDITION_TIMEOUT_MS, condition: () -> Boolean) {
    withContext(Dispatchers.Default) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(AWAIT_CONDITION_POLL_MS)
        }
    }
}

/** [awaitCondition] for a caller that is not itself suspending. */
fun awaitConditionBlocking(timeoutMs: Long = AWAIT_CONDITION_TIMEOUT_MS, condition: () -> Boolean) =
    runBlocking { awaitCondition(timeoutMs, condition) }
