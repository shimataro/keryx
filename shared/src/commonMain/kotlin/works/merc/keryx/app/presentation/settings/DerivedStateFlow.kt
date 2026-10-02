package works.merc.keryx.app.presentation.settings

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A read-only [StateFlow] whose [value] is [compute]d from other state flows on every read, and
 * whose collectors receive [changes] (deduplicated) — a derived value that, unlike one produced by
 * `stateIn`, can never be observed out of step with its inputs.
 *
 * Only the `settings` package uses it today; if another package needs it, move it up to `presentation/`.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class DerivedStateFlow<T>(
    private val compute: () -> T,
    private val changes: Flow<T>,
) : StateFlow<T> {
    override val value: T get() = compute()
    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        changes.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}
