package works.merc.keryx.app.tray

import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import works.merc.keryx.app.core.APP_NAME
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
    private val pendingIds = PendingNotificationIds()

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
                appName = APP_NAME,
                // 0 = never replace an earlier notification, so successive "N new articles"
                // notices stack the way the AWT balloons did.
                replacesId = UInt32(0),
                appIcon = "",
                summary = summary,
                body = body,
                // "default" is the conventional action key most notification daemons invoke when
                // the notification body itself is clicked, rather than a rendered button.
                actions = listOf("default", ""),
                hints = hints,
                // -1 = let the daemon apply its own default timeout.
                expireTimeout = -1,
            )
        }.onSuccess { id -> pendingIds.add(id) }
            .onFailure { Log.warn(LOG_TAG, "Could not deliver a desktop notification", it) }
    }

    /**
     * Returns `true` if [id] belongs to a notification this instance sent (and forgets it),
     * `false` if it belongs to some other application's notification - see [PendingNotificationIds].
     */
    fun consumeIfOwn(id: UInt32): Boolean = pendingIds.consume(id)
}
