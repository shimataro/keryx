package works.merc.keryx.app.platform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberNotificationPermissionRequester(): () -> Unit {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return remember { {} }

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return remember(context) {
        {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            // Requesting an already-granted permission is harmless but pointless; skipping it also
            // avoids a redundant system-side check on every call (e.g. every time the toggle in
            // NotificationsTab is flipped back on).
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
