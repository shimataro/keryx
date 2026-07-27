package works.merc.keryx.app.tray

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrayPixmapTest {
    /** A single pixel whose channels are all distinct, so any byte reordering is visible. */
    private val samplePixel = 0x80123456.toInt()

    private fun singlePixel(argb: Int = samplePixel): BufferedImage =
        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, argb) }

    private fun bytes(vararg values: Int): List<Byte> = values.map { it.toByte() }

    // --- SNI IconPixmap: big-endian ARGB32 ---

    @Test
    fun `sni pixmap encodes ARGB32 in network byte order`() {
        val pixmap = toSniPixmap(singlePixel())
        assertEquals(bytes(0x80, 0x12, 0x34, 0x56), pixmap.data.toList())
    }

    @Test
    fun `sni pixmap keeps fully transparent pixels transparent`() {
        // The regression guard for the reported bug: a transparent pixel must stay
        // transparent instead of being flattened onto an opaque background.
        val pixmap = toSniPixmap(singlePixel(argb = 0x00000000))
        assertEquals(bytes(0x00, 0x00, 0x00, 0x00), pixmap.data.toList())
    }

    @Test
    fun `sni pixmap declares the encoded dimensions and has no row padding`() {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)
        val pixmap = toSniPixmap(image)
        assertEquals(3, pixmap.width)
        assertEquals(2, pixmap.height)
        assertEquals(3 * 2 * 4, pixmap.data.size)
    }

    @Test
    fun `sni pixmap is row-major`() {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xFF000001.toInt())
        image.setRGB(1, 0, 0xFF000002.toInt())
        image.setRGB(0, 1, 0xFF000003.toInt())
        image.setRGB(1, 1, 0xFF000004.toInt())

        val blueChannel = toSniPixmap(image).data.filterIndexed { index, _ -> index % 4 == 3 }
        assertEquals(bytes(1, 2, 3, 4), blueChannel)
    }

    @Test
    fun `toSniPixmaps publishes one square entry per requested size`() {
        val pixmaps = toSniPixmaps(BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), intArrayOf(22, 24))
        assertEquals(2, pixmaps.size)
        assertEquals(listOf(22, 24), pixmaps.map { it.width })
        assertEquals(listOf(22, 24), pixmaps.map { it.height })
        pixmaps.forEach { assertEquals(it.width * it.height * 4, it.data.size) }
    }

    @Test
    fun `default sizes are published smallest first`() {
        assertEquals(SNI_ICON_SIZES.toList(), SNI_ICON_SIZES.sorted())
    }

    // --- Notification image-data: RGBA, deliberately NOT the SNI order ---

    @Test
    fun `notification image data encodes RGBA, not ARGB`() {
        // Exactly the reverse of `sni pixmap encodes ARGB32 in network byte order` for the
        // same input - the two encoders must never be swapped.
        val imageData = toNotificationImageData(singlePixel())
        assertEquals(bytes(0x12, 0x34, 0x56, 0x80), imageData.data.toList())
    }

    @Test
    fun `notification image data describes a 4-channel 8-bit RGBA buffer`() {
        val imageData = toNotificationImageData(BufferedImage(5, 3, BufferedImage.TYPE_INT_ARGB))
        assertEquals(5, imageData.width)
        assertEquals(3, imageData.height)
        assertEquals(5 * 4, imageData.rowStride)
        assertEquals(8, imageData.bitsPerSample)
        assertEquals(4, imageData.channels)
        assertTrue(imageData.hasAlpha)
        assertEquals(imageData.height * imageData.rowStride, imageData.data.size)
    }

    // --- scaleToSquare ---

    @Test
    fun `scaleToSquare preserves transparency`() {
        // Fails if anyone reintroduces a background fill - the AWT bug being worked around.
        val source = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        source.setRGB(32, 32, 0xFFFF0000.toInt())

        val scaled = scaleToSquare(source, 22)

        assertEquals(22, scaled.width)
        assertEquals(22, scaled.height)
        assertEquals(0, scaled.getRGB(0, 0) ushr 24, "the untouched corner must stay fully transparent")
    }

    // --- unread dot / bus name ---

    @Test
    fun `hasUnreadDot is true only for positive counts`() {
        assertFalse(hasUnreadDot(-1))
        assertFalse(hasUnreadDot(0))
        assertTrue(hasUnreadDot(1))
        assertTrue(hasUnreadDot(99))
        assertTrue(hasUnreadDot(100))
        assertTrue(hasUnreadDot(Long.MAX_VALUE))
    }

    @Test
    fun `bus name follows the StatusNotifierItem naming convention`() {
        assertEquals("org.kde.StatusNotifierItem-4321-1", sniBusName(4321L))
    }
}
