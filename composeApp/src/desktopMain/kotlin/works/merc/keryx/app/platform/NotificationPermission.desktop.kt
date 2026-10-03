package works.merc.keryx.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Desktop has no runtime notification permission: always granted, and a request succeeds at once. */
@Composable
actual fun rememberNotificationPermission(onResult: (granted: Boolean) -> Unit): NotificationPermissionController {
    val currentOnResult by rememberUpdatedState(onResult)
    return remember {
        object : NotificationPermissionController {
            override val isGranted: Boolean = true
            override fun request() = currentOnResult(true)
            override fun openSystemSettings() = Unit
        }
    }
}
