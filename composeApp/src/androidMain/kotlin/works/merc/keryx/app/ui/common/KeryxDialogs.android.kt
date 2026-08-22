package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Android [KeryxAlertDialog]: a plain M3 [AlertDialog] — none of the desktop actual's `DialogWindow`
 * / heavyweight-WebView-interop concerns apply here (there is no heavyweight AWT panel that could
 * paint over a Compose `Popup` on Android).
 *
 * [modal] is unused: unlike desktop's macOS-style non-modal "About" panel, an Android dialog is
 * always modal at the window-manager level — there is no non-blocking dialog concept to opt out
 * into, and a modal About screen is the ordinary, expected pattern on this platform.
 */
@Composable
actual fun KeryxAlertDialog(
    onDismissRequest: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
    dismissText: String?,
    title: String?,
    titleAction: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
    containerColor: Color,
    tonalElevation: Dp,
    modal: Boolean,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmText)
            }
        },
        dismissButton = dismissText?.let { label ->
            { TextButton(onClick = onDismissRequest) { Text(label) } }
        },
        title = title?.let { t ->
            {
                if (titleAction != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(t)
                        titleAction()
                    }
                } else {
                    Text(t)
                }
            }
        },
        text = text,
        // Unspecified is this function's own "let the caller's design language decide" sentinel
        // (see the commonMain KDoc); mirrors the desktop actual's fallback exactly.
        containerColor = if (containerColor.isUnspecified) MaterialTheme.colorScheme.surface else containerColor,
        tonalElevation = tonalElevation,
    )
}

/**
 * Android [KeryxTabDialog]: a near-fullscreen [Dialog] hosting the shared [KeryxDialogTabBar] (see
 * `KeryxDialogs.kt`) above the selected tab's content. The desktop actual's macOS-System-Settings
 * styling (traffic-light-adjacent title mirroring, fixed small window size) has no Android
 * equivalent; this only needs to host the same tab-switching behavior in a shape that fits a phone
 * or tablet screen. Revisit alongside the Settings screen's own adaptive-layout work (Phase 2) —
 * a full-screen Settings destination may replace this dialog wrapper entirely.
 */
@Composable
actual fun KeryxTabDialog(
    onDismissRequest: () -> Unit,
    tabs: List<KeryxDialogTab>,
    selectedTabId: String,
    onSelectTab: (String) -> Unit,
    content: @Composable (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                KeryxDialogTabBar(
                    tabs = tabs,
                    selectedTabId = selectedTabId,
                    onSelectTab = onSelectTab,
                )
                content(selectedTabId)
            }
        }
    }
}
