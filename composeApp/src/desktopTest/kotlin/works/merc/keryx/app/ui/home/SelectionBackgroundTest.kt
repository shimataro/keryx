package works.merc.keryx.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SelectionBackgroundTest {

    private fun colorFor(selected: Boolean, focused: Boolean): Pair<Color, Color> {
        var actual = Color.Unspecified
        var primary = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    primary = MaterialTheme.colorScheme.primary
                    actual = selectionBackground(selected, focused)
                }
            }
        }
        return actual to primary
    }

    private fun contentColorFor(selected: Boolean, focused: Boolean): Pair<Color?, Color> {
        var actual: Color? = Color.Unspecified
        var onPrimary = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    onPrimary = MaterialTheme.colorScheme.onPrimary
                    actual = selectionContentColorOrNull(selected, focused)
                }
            }
        }
        return actual to onPrimary
    }

    @Test
    fun selectedAndFocusedIsFullStrengthPrimary() {
        val (actual, primary) = colorFor(selected = true, focused = true)
        assertEquals(primary, actual)
    }

    @Test
    fun selectedButNotFocusedIsDimmedPrimary() {
        val (actual, primary) = colorFor(selected = true, focused = false)
        assertEquals(primary.copy(alpha = 0.4f), actual)
    }

    @Test
    fun notSelectedIsTransparentWhenFocused() {
        assertEquals(Color.Transparent, colorFor(selected = false, focused = true).first)
    }

    @Test
    fun notSelectedIsTransparentWhenNotFocused() {
        assertEquals(Color.Transparent, colorFor(selected = false, focused = false).first)
    }

    @Test
    fun contentColorIsOnPrimaryWhenSelectedAndFocused() {
        val (actual, onPrimary) = contentColorFor(selected = true, focused = true)
        assertEquals(onPrimary, actual)
    }

    @Test
    fun contentColorIsNullWhenSelectedButNotFocused() {
        assertNull(contentColorFor(selected = true, focused = false).first)
    }

    @Test
    fun contentColorIsNullWhenNotSelected() {
        assertNull(contentColorFor(selected = false, focused = true).first)
    }

    // --- tone-aware overloads (rows that can render the same feed more than once) ---

    private fun colorFor(tone: RowSelectionTone, focused: Boolean): Pair<Color, Color> {
        var actual = Color.Unspecified
        var primary = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    primary = MaterialTheme.colorScheme.primary
                    actual = selectionBackground(tone, focused)
                }
            }
        }
        return actual to primary
    }

    private fun contentColorFor(tone: RowSelectionTone, focused: Boolean): Pair<Color?, Color> {
        var actual: Color? = Color.Unspecified
        var onPrimary = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    onPrimary = MaterialTheme.colorScheme.onPrimary
                    actual = selectionContentColorOrNull(tone, focused)
                }
            }
        }
        return actual to onPrimary
    }

    @Test
    fun primaryToneMatchesTheBooleanSelectedBackgroundInBothFocusStates() {
        // A feed with no tag-nested duplicate must look exactly as it did before tones existed.
        val (focusedColor, primary) = colorFor(RowSelectionTone.PRIMARY, focused = true)
        assertEquals(primary, focusedColor)
        val (unfocusedColor, primaryAgain) = colorFor(RowSelectionTone.PRIMARY, focused = false)
        assertEquals(primaryAgain.copy(alpha = 0.4f), unfocusedColor)
    }

    @Test
    fun secondaryToneIsAFainterPrimaryTintRegardlessOfFocus() {
        val (focusedColor, primary) = colorFor(RowSelectionTone.SECONDARY, focused = true)
        assertEquals(primary.copy(alpha = SECONDARY_SELECTION_ALPHA), focusedColor)
        val (unfocusedColor, primaryAgain) = colorFor(RowSelectionTone.SECONDARY, focused = false)
        assertEquals(primaryAgain.copy(alpha = SECONDARY_SELECTION_ALPHA), unfocusedColor)
    }

    @Test
    fun secondaryToneIsClearlyFainterThanAnUnfocusedPrimaryRow() {
        // The whole point of the tone: a duplicate row must not be mistakable for the focused one.
        assertTrue(SECONDARY_SELECTION_ALPHA < 0.4f)
    }

    @Test
    fun noneToneIsTransparentInBothFocusStates() {
        assertEquals(Color.Transparent, colorFor(RowSelectionTone.NONE, focused = true).first)
        assertEquals(Color.Transparent, colorFor(RowSelectionTone.NONE, focused = false).first)
    }

    @Test
    fun toneContentColorIsOnPrimaryOnlyForAFocusedPrimaryRow() {
        val (actual, onPrimary) = contentColorFor(RowSelectionTone.PRIMARY, focused = true)
        assertEquals(onPrimary, actual)
        assertNull(contentColorFor(RowSelectionTone.PRIMARY, focused = false).first)
        assertNull(contentColorFor(RowSelectionTone.SECONDARY, focused = true).first)
        assertNull(contentColorFor(RowSelectionTone.SECONDARY, focused = false).first)
        assertNull(contentColorFor(RowSelectionTone.NONE, focused = true).first)
    }
}
