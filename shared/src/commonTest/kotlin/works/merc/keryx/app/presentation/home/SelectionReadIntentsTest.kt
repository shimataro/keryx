package works.merc.keryx.app.presentation.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SelectionReadIntentsTest {

    @Test
    fun recordIsANoOpWhileNoHydrationIsInFlight() {
        val intents = SelectionReadIntents()

        intents.record("a1", read = false)
        val seq = intents.begin()

        assertNull(intents.since("a1", seq))
        assertNull(intents.since("a1", -1L))
    }

    @Test
    fun sinceReturnsOnlyAnIntentMadeAfterTheCapturedSequence() {
        val intents = SelectionReadIntents()
        val first = intents.begin()
        intents.record("a1", read = false)
        val second = intents.begin()

        assertEquals(false, intents.since("a1", first))
        assertNull(intents.since("a1", second), "an intent made before a selection must not override it")
        assertNull(intents.since("a2", first), "intents are per article")
    }

    @Test
    fun aLaterIntentForTheSameArticleSupersedesTheEarlierOne() {
        val intents = SelectionReadIntents()
        val seq = intents.begin()
        intents.record("a1", read = false)
        val afterUnread = intents.begin()
        intents.record("a1", read = true)

        assertEquals(true, intents.since("a1", seq))
        assertEquals(true, intents.since("a1", afterUnread))
    }

    @Test
    fun intentsSurviveUntilTheLastInFlightHydrationEnds() {
        val intents = SelectionReadIntents()
        val seq = intents.begin()
        intents.begin()
        intents.record("a1", read = false)

        intents.end()
        assertEquals(false, intents.since("a1", seq), "one hydration is still in flight")

        intents.end()
        assertNull(intents.since("a1", seq), "the map is cleared once none is in flight")
        intents.record("a1", read = false)
        assertNull(intents.since("a1", seq), "and nothing is recorded until the next begin()")
    }

    @Test
    fun endWithoutAMatchingBeginFails() {
        val intents = SelectionReadIntents()
        intents.begin()
        intents.end()

        assertFailsWith<IllegalStateException> { intents.end() }
    }

    @Test
    fun pendingReadIntentCountsOnlyAnIntentOnTheCursorsArticleMadeAfterItsSelection() {
        val intents = SelectionReadIntents()
        intents.begin()
        intents.record("a1", read = false)
        val cursorSeq = intents.currentSeq
        intents.record("a2", read = false)
        intents.record("a3", read = true)

        val snapshot = intents.state.value
        assertEquals(false, pendingReadIntent(snapshot, "a2", cursorSeq))
        assertEquals(true, pendingReadIntent(snapshot, "a3", cursorSeq))
        assertNull(pendingReadIntent(snapshot, "a1", cursorSeq), "made before the selection")
        assertNull(pendingReadIntent(snapshot, "a4", cursorSeq), "no intent for it")
        assertNull(pendingReadIntent(snapshot, null, cursorSeq), "nothing selected")
        assertNull(pendingReadIntent(snapshot, "a2", Long.MAX_VALUE), "a cursor no selection set")
    }

    @Test
    fun stateEmitsEachRecordAndIsClearedWhenTheLastHydrationEnds() {
        val intents = SelectionReadIntents()
        intents.begin()
        intents.record("a1", read = false)
        assertEquals(setOf("a1"), intents.state.value.keys)

        intents.end()
        assertEquals(emptyMap(), intents.state.value)
    }
}
