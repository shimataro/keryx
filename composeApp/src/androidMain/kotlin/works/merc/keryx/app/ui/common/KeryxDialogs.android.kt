package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_back

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
 * Android [KeryxTabDialog]: a near-fullscreen, modal [Dialog] hosting a real M3 `TopAppBar`
 * (back arrow + [title]) above a genuine `PrimaryScrollableTabRow`/`Tab` — `Primary` because M3
 * groups primary tabs directly under a top app bar (secondary tabs are for a row that shares
 * space with other content), and `Scrollable` so a tab's full label is never truncated by an
 * equal-width division: tab count varies (4–5, depending on which cloud providers and
 * update-check mechanism this build has), and `fontSizeScale` (see `SettingsViewModel`) can push
 * even a fixed set of labels past what a single screen width holds at "Large"/"Extra Large".
 * `Tab`'s own default M3 layout already stacks the icon above the label, and its
 * `indicator`/ripple/selection colors need no manual wiring, unlike the desktop actual's
 * hand-rolled tab bar (`KeryxDialogTabBar` in `KeryxDialogs.desktop.kt`, desktop-only since this
 * Android actual stopped sharing it). The desktop actual's macOS-System-Settings styling
 * (traffic-light-adjacent title mirroring, fixed small window size, non-blocking modeless window)
 * has no Android equivalent; this only needs to host the same tab-switching behavior in a shape
 * that fits a phone or tablet screen. Revisit alongside the Settings screen's own adaptive-layout
 * work (Phase 2) — a full-screen Settings destination may replace this dialog wrapper entirely,
 * though this `TopAppBar` already gives the user the same back-arrow-and-title experience a route
 * would, so that swap would be an internal refactor rather than a user-visible change.
 *
 * The back arrow's `onClick` is [onDismissRequest] itself — the same dismiss path the system back
 * gesture/button already goes through — because the `Dialog`'s own `Surface` fills the entire
 * screen, leaving no outside area for `DialogProperties.dismissOnClickOutside` (left at its
 * default `true`) to ever actually catch a tap. Built directly on M3's `TopAppBar`/`IconButton`
 * rather than through `KeryxPaneTopBar`/`TooltipIconButton`: both of those exist to replace a
 * hand-rolled bar at a **`commonMain`** call site shared across platforms (see their own KDoc),
 * but this call site is already Android-only, so routing through an expect/actual layer would
 * just reproduce this same `TopAppBar`/`IconButton` pair one indirection away.
 *
 * `PrimaryScrollableTabRow`/`Tab` rendering is delegated to the shared [KeryxDialogTabs]
 * helper so the icon/label logic stays in one place; only the surrounding container is
 * Android-specific. Both the `TopAppBar` and `PrimaryScrollableTabRow`'s default colors (and the
 * latter's bottom `HorizontalDivider()`) are left as-is rather than overridden to match the
 * surrounding `surfaceContainerLow` — same reasoning as [KeryxAlertDialog]'s Android `actual`
 * ignoring `containerColor`/`tonalElevation`: M3's own defaults are what reads as native chrome
 * here, not desktop's flat surface pattern.
 *
 * The `Dialog` window draws behind the system bars edge-to-edge like the rest of the app (see
 * `MainActivity`'s `enableEdgeToEdge()`), so its content applies its own `safeDrawingPadding()`
 * rather than relying on the window to inset it — the tonal background still fills the full
 * window behind the status/navigation bars.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun KeryxTabDialog(
    onDismissRequest: () -> Unit,
    tabs: List<KeryxDialogTab>,
    selectedTabId: String,
    onSelectTab: (String) -> Unit,
    title: String?,
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
                val backLabel = stringResource(Res.string.common_back)
                TopAppBar(
                    title = { if (title != null) Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onDismissRequest) {
                            KeryxIcon(KeryxIcons.ArrowBack, contentDescription = backLabel)
                        }
                    },
                )
                // rememberSelectedTabId (SettingsDialog.kt) always resolves selectedTabId to one
                // of tabs' ids, and tabs' shape is fixed for the dialog's whole lifetime (it only
                // depends on build-time constants — availableCloudTypes, selfUpdateCheckSupported)
                // — so this index is never -1 in practice.
                val selectedIndex = tabs.indexOfFirst { it.id == selectedTabId }
                PrimaryScrollableTabRow(selectedTabIndex = selectedIndex) {
                    KeryxDialogTabs(
                        tabs = tabs,
                        selectedTabId = selectedTabId,
                        onSelectTab = onSelectTab,
                    )
                }
                content(selectedTabId)
            }
        }
    }
}
