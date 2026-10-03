package works.merc.keryx.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/**
 * The OS-level permission to post notifications, as far as this app can see and ask for it.
 *
 * Only Android 13+ (`POST_NOTIFICATIONS`) has a runtime permission to ask for. Everywhere else
 * (desktop, Android below 13) [isGranted] is always `true`, [request] reports `true` at once and
 * [openSystemSettings] does nothing, so a flow built on this never shows anything there.
 */
@Stable
interface NotificationPermissionController {
    /**
     * Whether the permission is currently granted. Snapshot-observable, and re-read whenever the
     * app resumes, so a grant or revocation made in the OS settings is picked up on return.
     */
    val isGranted: Boolean

    /**
     * Asks the OS for the permission. The outcome is delivered to the `onResult` given to
     * [rememberNotificationPermission] (not to this call), so it still arrives after an Android
     * configuration change recreates the composition while the system dialog is up. When the
     * permission is already granted the result is reported as `true` without asking.
     */
    fun request()

    /** Opens this app's notification settings in the OS, where a permanently denied permission can be granted. */
    fun openSystemSettings()
}

/**
 * Remembers this platform's [NotificationPermissionController]; [onResult] receives the outcome of
 * every [NotificationPermissionController.request] made through it (`true` = granted).
 */
@Composable
expect fun rememberNotificationPermission(onResult: (granted: Boolean) -> Unit): NotificationPermissionController
