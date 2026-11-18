package works.merc.keryx.app.presentation.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeSearchStateTest {

    private val state = HomeSearchState()

    @Test
    fun startsWithEmptyQueryClosedBarAndNoFocusRequest() {
        assertEquals("", state.query.value)
        assertFalse(state.barVisible.value)
        assertFalse(state.pendingFocus.value)
    }

    @Test
    fun setQueryIsReflected() {
        state.setQuery("kotlin")
        assertEquals("kotlin", state.query.value)
        state.setQuery("")
        assertEquals("", state.query.value)
    }

    @Test
    fun setBarVisibleOpensAndClosesTheBar() {
        state.setBarVisible(true)
        assertTrue(state.barVisible.value)
        state.setBarVisible(false)
        assertFalse(state.barVisible.value)
    }

    @Test
    fun focusRequestStaysLatchedUntilConsumed() {
        state.requestFocus()
        assertTrue(state.pendingFocus.value)
        // Opening the bar (the composable that owns the field has not composed yet) keeps it.
        state.setBarVisible(true)
        assertTrue(state.pendingFocus.value)

        state.consumeFocusRequest()
        assertFalse(state.pendingFocus.value)
    }

    @Test
    fun closingTheBarClearsAnUnconsumedFocusRequest() {
        state.setBarVisible(true)
        state.requestFocus()

        state.setBarVisible(false)

        assertFalse(state.pendingFocus.value)
    }

    @Test
    fun closingTheBarKeepsTheQuery() {
        state.setQuery("abc")
        state.setBarVisible(true)
        state.setBarVisible(false)
        assertEquals("abc", state.query.value)
    }
}
