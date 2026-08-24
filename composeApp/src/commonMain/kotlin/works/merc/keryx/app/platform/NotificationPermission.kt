package works.merc.keryx.app.platform

import androidx.compose.runtime.Composable

/**
 * Returns a function that requests the OS notification permission when called, if the platform
 * has one to request (Android 13+'s `POST_NOTIFICATIONS`) and it isn't already granted. A no-op
 * everywhere else (desktop, or an already-decided/pre-13 Android device), so call sites can invoke
 * it unconditionally without checking the platform themselves.
 */
@Composable
expect fun rememberNotificationPermissionRequester(): () -> Unit
