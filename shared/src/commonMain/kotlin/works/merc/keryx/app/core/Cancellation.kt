package works.merc.keryx.app.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] (returning `kotlin.Result`, not this package's own [Result]), except that a [CancellationException] is rethrown rather than captured.
 *
 * A plain `runCatching` around a suspending call turns the coroutine's own cancellation into an
 * ordinary failure, so the caller carries on — logging it as an error, or concluding "nothing
 * found" — after it has been told to stop. This is the one place that says otherwise.
 *
 * `inline`, so the block may suspend. Captures every other [Throwable], as `runCatching` does; use an
 * explicit `catch (e: Exception)` where errors must not be swallowed.
 */
internal inline fun <T> runCatchingUnlessCancelled(block: () -> T): kotlin.Result<T> = try {
    kotlin.Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    kotlin.Result.failure(e)
}
