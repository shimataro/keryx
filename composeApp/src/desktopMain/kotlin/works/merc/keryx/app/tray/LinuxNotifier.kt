package works.merc.keryx.app.tray

import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import works.merc.keryx.app.core.Log
import java.awt.image.BufferedImage

private const val LOG_TAG = "LinuxNotifier"

/** The notification icon is rendered at popup size, not panel size. */
private const val NOTIFICATION_ICON_SIZE = 48

/**
 * Desktop notifications through `org.freedesktop.Notifications`, replacing the AWT tray
 * balloon (`TrayIcon.displayMessage`) on the SNI path.
 *
 * When no notification daemon is present this is a no-op: `NewArticleNotifier` has already
 * recorded the same message in the in-app notification centre, so nothing is lost.
 */
internal class LinuxNotifier(
    private val connection: SniConnection,
    icon: BufferedImage?,
) {
    private val imageData = icon?.let { toNotificationImageData(scaleToSquare(it, NOTIFICATION_ICON_SIZE)) }

    /**
     * Delivers a desktop notification with the specified summary and body.
     *
     * @param summary The notification summary.
     * @param body The notification body.
     */
    fun notify(summary: String, body: String) {
        val notifications = connection.notifications() ?: return
        val hints: Map<String, Variant<*>> = imageData
            ?.let { mapOf("image-data" to Variant(it, "(iiibiiay)")) }
            ?: emptyMap()
        runCatching {
            notifications.Notify(
                appName = "Keryx",
                // 0 = never replace an earlier notification, so successive "N new articles"
                // notices stack the way the AWT balloons did.
                replacesId = UInt32(0),
                appIcon = "",
                summary = summary,
                body = body,
                actions = emptyList(),
                hints = hints,
                // -1 = let the daemon apply its own default timeout.
                expireTimeout = -1,
            )
        }.onFailure { Log.warn(LOG_TAG, "Could not deliver a desktop notification", it) }
    }
}
