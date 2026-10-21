package works.merc.keryx.app.presentation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [DerivedStateFlow], which `CloudSyncController.canSyncNow` (and every route gated on it)
 * relies on to never be observed out of step with its inputs.
 */
class DerivedStateFlowTest {

    private val a = MutableStateFlow(1)
    private val b = MutableStateFlow(2)
    private val sum = DerivedStateFlow(compute = { a.value + b.value }, changes = a.map { it + b.value })

    @Test
    fun valueIsRecomputedFromTheInputsOnEveryRead() {
        assertEquals(3, sum.value)

        // No collector and no dispatch in between: a stateIn copy would still read 3 here.
        b.value = 10

        assertEquals(11, sum.value)
    }

    @Test
    fun replayCacheIsTheCurrentValue() {
        a.value = 5

        assertEquals(listOf(7), sum.replayCache)
    }

    @Test
    fun collectorsUseTheGivenEquivalenceInsteadOfEquals() = runTest(UnconfinedTestDispatcher()) {
        // A SharedFlow upstream, unlike a StateFlow, passes an equal value on again — which is what
        // tells the two equivalences apart.
        val upstream = MutableSharedFlow<List<Int>>(replay = 1)
        val current = listOf(1)
        upstream.tryEmit(current)
        val byIdentity = DerivedStateFlow(
            compute = { upstream.replayCache.last() },
            changes = upstream,
            areEquivalent = ::sameListOrBothEmpty,
        )
        val byEquals = DerivedStateFlow(compute = { upstream.replayCache.last() }, changes = upstream)
        val identityReceived = mutableListOf<List<Int>>()
        val equalsReceived = mutableListOf<List<Int>>()
        val jobs = listOf(
            launch { byIdentity.collect { identityReceived += it } },
            launch { byEquals.collect { equalsReceived += it } },
        )

        upstream.emit(current) // the very same instance: dropped by both
        upstream.emit(listOf(1)) // equal contents, a new instance: kept by identity, dropped by ==
        upstream.emit(listOf(2))

        assertEquals(listOf(listOf(1), listOf(1), listOf(2)), identityReceived)
        assertEquals(listOf(listOf(1), listOf(2)), equalsReceived)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun sameListOrBothEmptyIsIdentityOrEmptiness() {
        val list = listOf(1, 2)
        assertEquals(true, sameListOrBothEmpty(list, list))
        assertEquals(false, sameListOrBothEmpty(list, listOf(1, 2).toMutableList().also { it[1] = 3 }))
        assertEquals(true, sameListOrBothEmpty(emptyList<Int>(), mutableListOf()))
        assertEquals(false, sameListOrBothEmpty(emptyList(), listOf(1)))
    }

    @Test
    fun collectorsDoNotReceiveConsecutiveDuplicates() = runTest(UnconfinedTestDispatcher()) {
        val parity = DerivedStateFlow(compute = { a.value % 2 }, changes = a.map { it % 2 })
        val received = mutableListOf<Int>()
        val job = launch { parity.collect { received += it } }

        a.value = 3 // still odd
        a.value = 4
        a.value = 6 // still even
        a.value = 7

        assertEquals(listOf(1, 0, 1), received)
        job.cancel()
    }
}
