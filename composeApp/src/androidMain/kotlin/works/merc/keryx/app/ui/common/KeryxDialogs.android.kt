package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
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
 *
 * [containerColor] and [tonalElevation] are also unused: they exist so desktop callers can opt into
 * the app's own flat surface pattern (`surfaceContainerLow` + no elevation — see the `ui-guidelines`
 * skill), but on Android a dialog surface is exactly where M3's own tonal elevation reads as native.
 * Not passing either to [AlertDialog] lets `AlertDialogDefaults`' own values apply.
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
        // containerColor/tonalElevation deliberately NOT forwarded — see this function's own KDoc.
    )
}

/**
 * Android [KeryxTabDialog]: a near-fullscreen, modal [Dialog] hosting a genuine M3
 * `PrimaryScrollableTabRow`/`Tab` above the selected tab's content — `Primary` because this tab
 * row is the screen's own main navigation (there is no separate `TopAppBar` above it), and
 * `Scrollable` so a tab's full label is never truncated by an equal-width division: tab count
 * varies (4–5, depending on which cloud providers and update-check mechanism this build has), and
 * `fontSizeScale` (see `SettingsViewModel`) can push even a fixed set of labels past what a single
 * screen width holds at "Large"/"Extra Large". `Tab`'s own default M3 layout already stacks the
 * icon above the label, and its `indicator`/ripple/selection colors need no manual wiring, unlike
 * the desktop actual's hand-rolled tab bar (`KeryxDialogTabBar` in `KeryxDialogs.desktop.kt`,
 * desktop-only since this Android actual stopped sharing it). The desktop actual's
 * macOS-System-Settings styling (traffic-light-adjacent title mirroring, fixed small window size,
 * non-blocking modeless window) has no Android equivalent; this only needs to host the same
 * tab-switching behavior in a shape that fits a phone or tablet screen. Revisit alongside the
 * Settings screen's own adaptive-layout work (Phase 2) — a full-screen Settings destination may
 * replace this dialog wrapper entirely.
 *
 * `PrimaryScrollableTabRow`'s default bottom `HorizontalDivider()` and container/content colors
 * are left as-is rather than overridden to match the surrounding `surfaceContainerLow` — same
 * reasoning as [KeryxAlertDialog]'s Android `actual` ignoring `containerColor`/`tonalElevation`:
 * M3's own defaults are what reads as native chrome here, not desktop's flat surface pattern.
 *
 * The `Dialog` window draws behind the system bars edge-to-edge like the rest of the app (see
 * `MainActivity`'s `enableEdgeToEdge()`), so its content applies its own `safeDrawingPadding()`
 * rather than relying on the window to inset it — the tonal background still fills the full
 * window behind the status/navigation bars.
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
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                // rememberSelectedTabId (SettingsDialog.kt) always resolves selectedTabId to one
                // of tabs' ids, and tabs' shape is fixed for the dialog's whole lifetime (it only
                // depends on build-time constants — availableCloudTypes, selfUpdateCheckSupported)
                // — so this index is never -1 in practice.
                val selectedIndex = tabs.indexOfFirst { it.id == selectedTabId }
                PrimaryScrollableTabRow(selectedTabIndex = selectedIndex) {
                    tabs.forEach { tab ->
                        Tab(
                            selected = tab.id == selectedTabId,
                            onClick = { onSelectTab(tab.id) },
                            text = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            icon = { KeryxIcon(tab.icon, contentDescription = null) },
                        )
                    }
                }
                content(selectedTabId)
            }
        }
    }
}
