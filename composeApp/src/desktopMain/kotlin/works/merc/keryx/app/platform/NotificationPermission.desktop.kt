package works.merc.keryx.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** No-op: desktop has no runtime notification permission. */
@Composable
actual fun rememberNotificationPermissionRequester(): () -> Unit = remember { {} }
