package works.merc.keryx.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import works.merc.keryx.app.platform.isMacOs
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

private val BADGE_COLOR = Color(0xFF, 0x3B, 0x30)
private const val DOT_SIZE_RATIO = 0.3f

/** Badge height relative to the base icon's height, for [drawUnreadBadge]'s composited badge. */
private const val BADGE_HEIGHT_RATIO = 0.40f

/** Badge font size relative to badge height, in [paintBadge]. */
private const val BADGE_FONT_HEIGHT_RATIO = 0.48f

/** Extra badge width (beyond the text width) reserved as horizontal padding, in [paintBadge]. */
private const val BADGE_TEXT_PADDING_RATIO = 0.35f

/** Badge height relative to the canvas size, for [drawBadgeOnlyImage]'s standalone badge (no base icon). */
private const val BADGE_ONLY_HEIGHT_RATIO = 0.9f

/**
 * The badge number's font family. On macOS, AWT's logical `SansSerif` family
 * doesn't resolve to San Francisco (the system UI font used by native badges
 * like the Dock/notification badges), so `.AppleSystemUIFont` is used instead
 * when available. Falls back to `SansSerif` elsewhere or if that name isn't
 * resolvable on this JDK.
 */
private val BADGE_FONT_FAMILY: String by lazy {
    val macCandidate = ".AppleSystemUIFont"
    val available = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
    if (isMacOs && available.contains(macCandidate)) macCandidate else Font.SANS_SERIF
}

/** Returns the display label for an unread count, or `null` if no badge should be shown. */
fun unreadBadgeLabel(count: Long): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

/**
 * Draws a red rounded badge with a centered label.
 *
 * @param anchorRightX The x-coordinate of the badge's right edge.
 * @param anchorTopY The y-coordinate of the badge's top edge.
 * @param badgeHeight The badge height.
 * @param label The text displayed in the badge.
 */
private fun paintBadge(graphics: Graphics2D, anchorRightX: Float, anchorTopY: Float, badgeHeight: Float, label: String) {
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

    graphics.font = Font(BADGE_FONT_FAMILY, Font.BOLD, (badgeHeight * BADGE_FONT_HEIGHT_RATIO).toInt())
    val metrics = graphics.fontMetrics
    val textWidth = metrics.stringWidth(label)
    val badgeWidth = maxOf(badgeHeight, textWidth + badgeHeight * BADGE_TEXT_PADDING_RATIO)

    val x = anchorRightX - badgeWidth
    val y = anchorTopY
    val arc = badgeHeight

    graphics.color = BADGE_COLOR
    graphics.fill(RoundRectangle2D.Float(x, y, badgeWidth, badgeHeight, arc, arc))

    graphics.color = Color.WHITE
    val textX = x + (badgeWidth - textWidth) / 2f
    val textY = y + (badgeHeight - metrics.height) / 2f + metrics.ascent
    graphics.drawString(label, textX, textY)
}

/**
 * Draws an unread-count badge at the top-right corner of a copy of [base].
 *
 * @param base The image to copy and decorate.
 * @param count The unread-item count used to determine the badge label.
 * @return A new image containing [base] and, when applicable, the unread badge.
 */
fun drawUnreadBadge(base: BufferedImage, count: Long): BufferedImage {
    val result = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = result.createGraphics()
    graphics.drawImage(base, 0, 0, null)

    val label = unreadBadgeLabel(count)
    if (label == null) {
        graphics.dispose()
        return result
    }

    val badgeHeight = base.height * BADGE_HEIGHT_RATIO
    val inset = 0f
    paintBadge(graphics, anchorRightX = base.width - inset, anchorTopY = inset, badgeHeight = badgeHeight, label = label)

    graphics.dispose()
    return result
}

/**
 * Draws a small unread-indicator dot (no digits) onto a copy of [base],
 * anchored to the top-right corner - the conventional macOS menu-bar-icon
 * pattern for status items too small to render legible digits (tray/system
 * tray icons render at ~16-22px, where digits from [drawUnreadBadge] become
 * illegible). Returns an unmodified copy (never [base] itself) when [count]
 * &lt;= 0.
 */
fun drawUnreadDot(base: BufferedImage, count: Long): BufferedImage {
    val result = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = result.createGraphics()
    graphics.drawImage(base, 0, 0, null)

    if (unreadBadgeLabel(count) == null) {
        graphics.dispose()
        return result
    }

    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    val dotSize = base.height * DOT_SIZE_RATIO
    graphics.color = BADGE_COLOR
    graphics.fill(Ellipse2D.Float(base.width - dotSize, 0f, dotSize, dotSize))

    graphics.dispose()
    return result
}

/**
 * Draws an unread-count badge on a transparent square image for use as a taskbar overlay.
 *
 * @param count The unread item count.
 * @param size The width and height of the generated image in pixels.
 * @return The badge image, or `null` when [count] is less than or equal to zero.
 */
fun drawBadgeOnlyImage(count: Long, size: Int = 64): BufferedImage? {
    val label = unreadBadgeLabel(count) ?: return null
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    val badgeHeight = size * BADGE_ONLY_HEIGHT_RATIO
    val margin = (size - badgeHeight) / 2f
    paintBadge(graphics, anchorRightX = size - margin, anchorTopY = margin, badgeHeight = badgeHeight, label = label)
    graphics.dispose()
    return image
}

/**
 * Loads a Compose Resources [DrawableResource] into a [BufferedImage].
 *
 * Loads synchronously (like `painterResource` itself does internally via
 * `runBlocking` on desktop) so the result is available from the very first
 * composition - callers must not skip rendering while waiting for it.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun rememberDrawableImage(resource: DrawableResource): BufferedImage? = remember(resource) {
    val environment = getSystemResourceEnvironment()
    val bytes = runBlocking { getDrawableResourceBytes(environment, resource) }
    ImageIO.read(ByteArrayInputStream(bytes))
}
