package works.merc.keryx.app.presentation

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
        val values = MutableStateFlow(listOf(1))
        val byIdentity = DerivedStateFlow(
            compute = { values.value },
            changes = values.map { it },
            areEquivalent = ::sameListOrBothEmpty,
        )
        val received = mutableListOf<List<Int>>()
        val job = launch { byIdentity.collect { received += it } }

        values.value = listOf(2)
        values.value = listOf(3)

        assertEquals(listOf(listOf(1), listOf(2), listOf(3)), received)
        job.cancel()
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
