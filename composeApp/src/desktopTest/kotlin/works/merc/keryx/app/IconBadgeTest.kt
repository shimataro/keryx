package works.merc.keryx.app

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IconBadgeTest {
    private fun sampleBase(): BufferedImage {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color.BLUE
        graphics.fillRect(0, 0, 64, 64)
        graphics.dispose()
        return image
    }

    private fun pixels(image: BufferedImage): IntArray =
        image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    @Test
    fun `count zero returns visually identical image`() {
        val base = sampleBase()
        val result = drawUnreadBadge(base, count = 0)
        assertEquals(base.width, result.width)
        assertEquals(base.height, result.height)
        assertEquals(pixels(base).toList(), pixels(result).toList())
    }

    @Test
    fun `negative count returns visually identical image`() {
        val base = sampleBase()
        val result = drawUnreadBadge(base, count = -1)
        assertEquals(pixels(base).toList(), pixels(result).toList())
    }

    @Test
    fun `positive count draws a badge without changing dimensions`() {
        val base = sampleBase()
        val result = drawUnreadBadge(base, count = 5)
        assertEquals(base.width, result.width)
        assertEquals(base.height, result.height)
        assertTrue(pixels(base).toList() != pixels(result).toList())
    }

    @Test
    fun `does not mutate the input image`() {
        val base = sampleBase()
        val before = pixels(base).toList()
        drawUnreadBadge(base, count = 5)
        assertEquals(before, pixels(base).toList())
    }

    @Test
    fun `very large counts do not throw and preserve dimensions`() {
        val base = sampleBase()
        val result = drawUnreadBadge(base, count = Long.MAX_VALUE)
        assertEquals(base.width, result.width)
        assertEquals(base.height, result.height)

        val hundred = drawUnreadBadge(base, count = 100)
        assertEquals(base.width, hundred.width)
        assertEquals(base.height, hundred.height)
    }

    @Test
    fun `badge is anchored near the top edge, not the bottom`() {
        // The badge is a rounded pill/circle inset from the icon edges, so its
        // bounding box corner pixels aren't necessarily covered - checking exactly
        // where the changed region *starts* vertically is what actually
        // distinguishes "anchored to top" from "anchored to bottom" (which would
        // start around row height/2, not near row 0).
        val base = sampleBase()
        val result = drawUnreadBadge(base, count = 5)

        val changedRows = (0 until base.height).filter { y ->
            (0 until base.width).any { x -> result.getRGB(x, y) != base.getRGB(x, y) }
        }
        assertTrue(changedRows.isNotEmpty(), "expected the badge to change some pixels")
        assertTrue(changedRows.first() <= 5, "expected the badge to start near the top edge, but it started at row ${changedRows.first()}")
    }

    @Test
    fun `drawUnreadDot count zero returns visually identical image`() {
        val base = sampleBase()
        val result = drawUnreadDot(base, count = 0)
        assertEquals(base.width, result.width)
        assertEquals(base.height, result.height)
        assertEquals(pixels(base).toList(), pixels(result).toList())
    }

    @Test
    fun `drawUnreadDot positive count draws a dot near the top-right without changing dimensions`() {
        val base = sampleBase()
        val result = drawUnreadDot(base, count = 5)
        assertEquals(base.width, result.width)
        assertEquals(base.height, result.height)
        assertTrue(pixels(base).toList() != pixels(result).toList())

        val changedRows = (0 until base.height).filter { y ->
            (0 until base.width).any { x -> result.getRGB(x, y) != base.getRGB(x, y) }
        }
        assertTrue(changedRows.isNotEmpty(), "expected the dot to change some pixels")
        assertTrue(changedRows.first() <= 5, "expected the dot to start near the top edge, but it started at row ${changedRows.first()}")
    }

    @Test
    fun `drawBadgeOnlyImage returns null when there is no unread count`() {
        assertEquals(null, drawBadgeOnlyImage(count = 0, size = 32))
        assertEquals(null, drawBadgeOnlyImage(count = -1, size = 32))
    }

    @Test
    fun `drawBadgeOnlyImage draws a non-transparent badge at the requested size`() {
        val size = 32
        val image = drawBadgeOnlyImage(count = 5, size = size)
        requireNotNull(image)
        assertEquals(size, image.width)
        assertEquals(size, image.height)

        val hasOpaquePixel = (0 until size).any { x ->
            (0 until size).any { y -> (image.getRGB(x, y) ushr 24) != 0 }
        }
        assertTrue(hasOpaquePixel, "expected at least one non-transparent pixel")
    }
}
