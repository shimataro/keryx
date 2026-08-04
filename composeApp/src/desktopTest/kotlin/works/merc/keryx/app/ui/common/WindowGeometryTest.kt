package works.merc.keryx.app.ui.common

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [centeredPosition]'s owner-centering and screen-bounds clamp. Uses a real, never-shown
 * [Frame] as the owner — [java.awt.Window]'s constructor eagerly resolves a
 * [java.awt.GraphicsConfiguration] from the default screen device, so `frame.graphicsConfiguration`
 * is already meaningful before the frame has a peer, and `currentScreenBounds` can read real bounds
 * from it.
 */
class WindowGeometryTest {

    private val screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice.defaultConfiguration.bounds

    @Test
    fun `centeredPosition returns PlatformDefault when owner is null`() {
        assertEquals(WindowPosition.PlatformDefault, centeredPosition(owner = null, size = DpSize(400.dp, 300.dp)))
    }

    @Test
    fun `centeredPosition centers over an owner that fits fully on screen`() {
        val owner = Frame().apply {
            setBounds(screenBounds.x, screenBounds.y, screenBounds.width / 2, screenBounds.height / 2)
        }
        val size = DpSize(100.dp, 80.dp)

        val position = centeredPosition(owner, size)

        val expectedX = owner.x.dp + (owner.width.dp - size.width) / 2f
        val expectedY = owner.y.dp + (owner.height.dp - size.height) / 2f
        assertEquals(expectedX, position.x)
        assertEquals(expectedY, position.y)
    }

    @Test
    fun `centeredPosition clamps to screen bounds when naive centering would spill off the right and bottom edges`() {
        // Owner sits at the screen's bottom-right corner, much smaller than the dialog, so naively
        // centering the dialog over it would place most of the dialog off-screen.
        val owner = Frame().apply {
            setBounds(screenBounds.x + screenBounds.width - 10, screenBounds.y + screenBounds.height - 10, 10, 10)
        }
        val size = DpSize(400.dp, 300.dp)

        val position = centeredPosition(owner, size)

        val maxX = screenBounds.x.dp + screenBounds.width.dp - size.width
        val maxY = screenBounds.y.dp + screenBounds.height.dp - size.height
        assertEquals(maxX, position.x)
        assertEquals(maxY, position.y)
    }
}
