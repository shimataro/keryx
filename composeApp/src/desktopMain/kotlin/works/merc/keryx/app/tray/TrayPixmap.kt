package works.merc.keryx.app.tray

import works.merc.keryx.app.unreadBadgeLabel
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * Icon sizes published in the SNI `IconPixmap` property, smallest first. 22 and 24 are the
 * usual KDE/GNOME panel sizes, 32/48 cover 2x-scaled panels, and 64 is the source asset's
 * own size. Hosts pick whichever entry fits.
 */
internal val SNI_ICON_SIZES = intArrayOf(22, 24, 32, 48, 64)

/**
 * The well-known bus name of our status notifier item.
 *
 * The trailing number is the item's index *within the process*; Keryx only ever publishes
 * one, so it is always 1. A D-Bus well-known name is released when its connection dies, and
 * `SingleInstanceCoordinator` already guarantees a single Keryx process, so no collision
 * retry is needed - a failing `RequestName` simply falls back to the AWT tray.
 */
internal fun sniBusName(pid: Long): String = "org.kde.StatusNotifierItem-$pid-1"

/**
 * Whether the tray glyph carries the unread dot. `drawUnreadDot` renders no digits, so the
 * dot looks identical for every positive count - this boolean is the only input that can
 * change the rendered tray icon, and therefore the only thing worth emitting `NewIcon` for.
 */
internal fun hasUnreadDot(count: Long): Boolean = unreadBadgeLabel(count) != null

/**
 * Scales [source] onto a fresh [size]x[size] `TYPE_INT_ARGB` canvas, preserving alpha.
 *
 * Deliberately does **not** fill the canvas first: it starts fully transparent and must stay
 * that way. Painting a background before the icon is precisely the AWT bug this whole code
 * path exists to avoid (`sun.awt.X11.XTrayIconPeer.IconCanvas.paint` fills with the component
 * background, which is why the AWT tray icon shows up inside a white box on X11).
 */
internal fun scaleToSquare(source: BufferedImage, size: Int): BufferedImage {
    val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = scaled.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    graphics.drawImage(source, 0, 0, size, size, null)
    graphics.dispose()
    return scaled
}

/**
 * Encodes [image] as one SNI pixmap: ARGB32 in network (big-endian) byte order, row-major,
 * no row padding.
 */
internal fun toSniPixmap(image: BufferedImage): SniPixmap {
    val width = image.width
    val height = image.height
    val data = ByteArray(width * height * 4)
    var index = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val argb = image.getRGB(x, y)
            data[index++] = (argb ushr 24).toByte()
            data[index++] = (argb ushr 16).toByte()
            data[index++] = (argb ushr 8).toByte()
            data[index++] = argb.toByte()
        }
    }
    return SniPixmap(width, height, data)
}

/** The whole `IconPixmap` payload: one entry per size in [sizes]. */
internal fun toSniPixmaps(image: BufferedImage, sizes: IntArray = SNI_ICON_SIZES): List<SniPixmap> =
    sizes.map { toSniPixmap(scaleToSquare(image, it)) }

/**
 * Encodes [image] for the `image-data` hint of `org.freedesktop.Notifications.Notify`.
 *
 * The freedesktop notification spec wants **RGBA, row-major** here - a different byte order
 * from [toSniPixmap]'s big-endian ARGB32. Keep the two apart.
 */
internal fun toNotificationImageData(image: BufferedImage): NotificationImageData {
    val width = image.width
    val height = image.height
    val data = ByteArray(width * height * 4)
    var index = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val argb = image.getRGB(x, y)
            data[index++] = (argb ushr 16).toByte()
            data[index++] = (argb ushr 8).toByte()
            data[index++] = argb.toByte()
            data[index++] = (argb ushr 24).toByte()
        }
    }
    return NotificationImageData(
        width = width,
        height = height,
        rowStride = width * 4,
        hasAlpha = true,
        bitsPerSample = 8,
        channels = 4,
        data = data,
    )
}
