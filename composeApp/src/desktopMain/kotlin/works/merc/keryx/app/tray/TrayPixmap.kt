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
 * Builds the well-known D-Bus name for a status notifier item.
 *
 * @param pid The process identifier used in the bus name.
 * @return The status notifier item's well-known D-Bus name.
 */
internal fun sniBusName(pid: Long): String = "org.kde.StatusNotifierItem-$pid-1"

/**
 * Determines whether the tray icon should display an unread indicator.
 *
 * @param count The number of unread items.
 * @return `true` if the unread indicator should be shown, `false` otherwise.
 */
internal fun hasUnreadDot(count: Long): Boolean = unreadBadgeLabel(count) != null

/**
 * Scales an image to a square canvas while preserving transparent areas.
 *
 * @param source The image to scale.
 * @param size The width and height of the resulting image.
 * @return A square ARGB image with the scaled source image.
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

/**
     * Creates SNI icon pixmaps for the requested square sizes.
     *
     * @param sizes The square icon sizes to generate.
     * @return One pixmap for each requested size, in the same order.
     */
internal fun toSniPixmaps(image: BufferedImage, sizes: IntArray = SNI_ICON_SIZES): List<SniPixmap> =
    sizes.map { toSniPixmap(scaleToSquare(image, it)) }

/**
 * Encodes [image] as RGBA pixel data for the freedesktop notification `image-data` hint.
 *
 * @param image The image to encode.
 * @return The encoded image data with its dimensions and pixel format metadata.
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
