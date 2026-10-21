package works.merc.keryx.app.presentation

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A read-only [StateFlow] whose [value] is [compute]d from other state flows on every read, and
 * whose collectors receive [changes] (deduplicated by [areEquivalent]) — a derived value that, unlike
 * one produced by `stateIn`, can never be observed out of step with its inputs.
 *
 * [areEquivalent] defaults to `==`. A large value whose upstream already drops equal consecutive
 * emissions (a list from a `StateFlow`) passes a cheaper test, since comparing it again on every
 * collector's every emission is paid for on the collector's thread.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class DerivedStateFlow<T>(
    private val compute: () -> T,
    private val changes: Flow<T>,
    private val areEquivalent: (T, T) -> Boolean = { old, new -> old == new },
) : StateFlow<T> {
    override val value: T get() = compute()
    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        changes.distinctUntilChanged(areEquivalent).collect(collector)
        awaitCancellation()
    }
}

/**
 * [DerivedStateFlow]'s `areEquivalent` for a list derived from a `StateFlow` of it: that flow has
 * already dropped equal consecutive values, so the same instance is unchanged, and so are two empty
 * lists (the step from "not loaded yet" to a first, empty emission). Anything else is a change —
 * without a structural comparison of every row.
 */
internal fun <T> sameListOrBothEmpty(old: List<T>, new: List<T>): Boolean =
    old === new || (old.isEmpty() && new.isEmpty())
