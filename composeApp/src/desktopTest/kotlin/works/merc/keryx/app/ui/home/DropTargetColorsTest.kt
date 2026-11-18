package works.merc.keryx.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class DropTargetColorsTest {

    private val container = Color(0xFF123456)
    private val onContainer = Color(0xFFABCDEF)

    private fun <T> compose(block: @Composable (primary: Color) -> T): T {
        var result: T? = null
        runDesktopComposeUiTest {
            setContent { MaterialTheme { result = block(MaterialTheme.colorScheme.primary) } }
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun aDropTargetUsesTheContainerColorEvenWhenSelectedOrDragSource() {
        val colors = compose {
            listOf(
                dropTargetBackground(true, selected = false, focused = false, containerColor = container),
                dropTargetBackground(true, selected = true, focused = true, containerColor = container, isDragSource = true),
            )
        }
        assertEquals(listOf(container, container), colors)
    }

    @Test
    fun aDragSourceIsTransparentSoTheDestinationReadsAsActive() {
        val color = compose {
            dropTargetBackground(false, selected = true, focused = true, containerColor = container, isDragSource = true)
        }
        assertEquals(Color.Transparent, color)
    }

    @Test
    fun otherwiseTheSelectionBackgroundApplies() {
        val (selected, plain, primary) = compose { primary ->
            listOf(
                dropTargetBackground(false, selected = true, focused = true, containerColor = container),
                dropTargetBackground(false, selected = false, focused = true, containerColor = container),
                primary,
            )
        }
        assertEquals(primary, selected)
        assertEquals(Color.Transparent, plain)
    }

    @Test
    fun contentColorFollowsTheSameThreeBranches() {
        val (target, source, selected, plain, onPrimary) = compose { _ ->
            listOf(
                dropTargetContentColorOrNull(true, selected = true, focused = true, onContainerColor = onContainer, isDragSource = true),
                dropTargetContentColorOrNull(false, selected = true, focused = true, onContainerColor = onContainer, isDragSource = true),
                dropTargetContentColorOrNull(false, selected = true, focused = true, onContainerColor = onContainer),
                dropTargetContentColorOrNull(false, selected = false, focused = true, onContainerColor = onContainer),
                MaterialTheme.colorScheme.onPrimary,
            )
        }
        assertEquals(onContainer, target)
        assertNull(source)
        assertEquals(onPrimary, selected)
        assertNull(plain)
    }
}
