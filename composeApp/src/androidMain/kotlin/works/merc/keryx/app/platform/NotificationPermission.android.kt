package works.merc.keryx.app.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

            override fun openSystemSettings() {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
}

private fun Context.hasNotificationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
