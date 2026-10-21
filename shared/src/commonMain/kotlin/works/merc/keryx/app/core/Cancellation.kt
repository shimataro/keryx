package works.merc.keryx.app.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * [runCatching] (returning `kotlin.Result`, not this package's own [Result]), except that a
 * [CancellationException] is rethrown rather than captured.
 *
 * A plain `runCatching` around a suspending call turns the coroutine's own cancellation into an
 * ordinary failure, so the caller carries on — logging it as an error, or concluding "nothing
 * found" — after it has been told to stop. This is the one place that says otherwise.
 *
 * `inline`, so the block may suspend. Captures every other [Throwable], as `runCatching` does.
 *
 * Deliberately not used where only an `Exception` should be caught (so an `Error` still escapes —
 * `FeedRepository.applyFavicon`, `runMaintenanceStep`), or where cancellation has side effects
 * of its own to run (`UpdateRepository`, `FeedFetcher`'s retry loop): those keep an explicit
 * `catch`.
 *
 * Public so `:composeApp` can use it too, but hidden from Swift: its `kotlin.Result` return type
 * has no useful Objective-C form, and the Apple app has no use for it.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
inline fun <T> runCatchingUnlessCancelled(block: () -> T): kotlin.Result<T> = try {
    kotlin.Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    kotlin.Result.failure(e)
}
