package works.merc.keryx.app.platform

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import works.merc.keryx.app.core.Log

private const val TAG = "NotificationPermission"

@Composable
actual fun rememberNotificationPermission(onResult: (granted: Boolean) -> Unit): NotificationPermissionController {
    val currentOnResult by rememberUpdatedState(onResult)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        // Below Android 13 notifications need no runtime permission.
        return remember {
            object : NotificationPermissionController {
                override val isGranted: Boolean = true
                override fun request() = currentOnResult(true)
                override fun openSystemSettings() = Unit
            }
        }
    }

    val context = LocalContext.current
    val granted = remember(context) { mutableStateOf(context.hasNotificationPermission()) }
    // Picks up a grant/revocation made in the OS settings (e.g. via openSystemSettings) on return.
    LifecycleResumeEffect(context) {
        granted.value = context.hasNotificationPermission()
        onPauseOrDispose {}
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted.value = context.hasNotificationPermission()
        currentOnResult(result)
    }
    return remember(context, launcher) {
        object : NotificationPermissionController {
            override val isGranted: Boolean get() = granted.value

            override fun request() {
                if (context.hasNotificationPermission()) {
                    granted.value = true
                    currentOnResult(true)
                } else {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            override fun openSystemSettings() = context.openNotificationSettings()
        }
    }
}

/**
 * Opens this app's notification settings page, falling back to its app-details page on a device
 * whose Settings app has no notification page (some OEM / restricted builds). Called from a click
 * handler, so a missing page must never throw: if neither page exists this only logs.
 */
private fun Context.openNotificationSettings() {
    val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    for (intent in listOf(notificationSettings, appDetails)) {
        if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            Log.warn(TAG, "No activity found for ${intent.action}", e)
        }
    }
}

private fun Context.hasNotificationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
