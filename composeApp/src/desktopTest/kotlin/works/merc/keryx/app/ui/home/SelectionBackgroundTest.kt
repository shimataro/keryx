package works.merc.keryx.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
