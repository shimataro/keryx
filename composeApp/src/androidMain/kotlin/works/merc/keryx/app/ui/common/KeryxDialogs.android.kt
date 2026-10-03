package works.merc.keryx.app.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_back
import works.merc.keryx.app.ui.theme.LocalAppDarkTheme
import works.merc.keryx.app.ui.theme.SyncSystemBarAppearance

/**
 * Android [KeryxAlertDialog]: a plain M3 [AlertDialog] — none of the desktop actual's `DialogWindow`
 * / heavyweight-WebView-interop concerns apply here (there is no heavyweight AWT panel that could
 * paint over a Compose `Popup` on Android).
 *
 * [modal] is unused: unlike desktop's macOS-style non-modal "About" panel, an Android dialog is
 * always modal at the window-manager level — there is no non-blocking dialog concept to opt out
 * into, and a modal About screen is the ordinary, expected pattern on this platform.
 *
 * [containerColor] is also unused: it exists so desktop callers can opt into the app's own flat
 * surface pattern (`surfaceContainerLow` + no elevation — see the `ui-guidelines` skill), but on
 * Android a dialog surface is exactly where M3's own tonal elevation reads as native. Not passing
 * it to [AlertDialog] lets `AlertDialogDefaults`' own values apply.
 *
 * [text] is wrapped in a `verticalScroll` [Column], matching the desktop actual: M3's
 * [AlertDialog] only constrains its text slot's height, it doesn't scroll it, so content taller
 * than the screen (a phone in landscape, a large font scale) would otherwise be clipped. A caller
 * embedding a lazy list must bound its height, as it already must on desktop.
 *
 * [destructive] colors the confirm [TextButton]'s content with `colorScheme.error`, M3's own
 * convention for a dialog action that deletes or resets something.
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
    modal: Boolean,
    destructive: Boolean,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) {
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
        text = text?.let { content ->
            { Column(Modifier.verticalScroll(rememberScrollState())) { content() } }
        },
        // containerColor deliberately NOT forwarded — see this function's own KDoc.
    )
}

/**
 * Android [KeryxTabDialog]: a near-fullscreen, modal [Dialog] following Android's own settings
 * pattern — a list of the categories that opens one as a detail screen — rather than a tab row.
 * Both screens are a real M3 `TopAppBar` with a back arrow above a body:
 * - **List** ([selectedTabId] `null`): the bar shows [title]; the body is one M3 [ListItem] per tab
 *   (its icon as `leadingContent`, its label as the headline — M3's one-line `ListItem` is already
 *   56dp tall). Tapping a row calls [onSelectTab] with that tab's id.
 * - **Detail** ([selectedTabId] set): the bar shows that tab's own label; the body is [content] for
 *   it inside a `verticalScroll` [Column] — content taller than the remaining space scrolls, mirroring
 *   desktop's own `verticalScroll` wrapper (`KeryxDialogs.desktop.kt`); unlike desktop's
 *   fixed-height dialog, this screen's available height varies with the device's own screen size.
 *   A [selectedTabId] matching none of [tabs] is shown as the list (`SettingsDialog`'s
 *   `resolveSettingsEntry` already maps such an id to `null`, so this is only a safety net).
 *
 * The switch between the two has no transition animation. The desktop actual's
 * macOS-System-Settings styling (traffic-light-adjacent title mirroring, fixed small window size,
 * non-blocking modeless window) has no Android equivalent.
 *
 * **Back** has one implementation, [goBackInTabDialog]: detail → list, list → [onDismissRequest].
 * Both the back arrow and the system back gesture/button call it. The system back reaches it through
 * a [BackHandler] inside the dialog's content, with `DialogProperties.dismissOnBackPress = false` so
 * the `Dialog` itself never dismisses on back behind it: Compose's `Dialog` is a `ComponentDialog`
 * whose own back callback is registered when the dialog is created, before its content composes, so
 * the content's [BackHandler] — resolved from the dialog's own view tree, not the Activity's — is
 * the later-added, and therefore first-served, handler. The arrow exists at all because the
 * `Dialog`'s own `Surface` fills the entire screen, leaving no outside area for
 * `DialogProperties.dismissOnClickOutside` (left at its default `true`) to ever actually catch a tap.
 * Built directly on M3's `TopAppBar`/`IconButton` rather than through
 * `KeryxPaneTopBar`/`TooltipIconButton`: both of those exist to replace a hand-rolled bar at a
 * **`commonMain`** call site shared across platforms (see their own KDoc), but this call site is
 * already Android-only, so routing through an expect/actual layer would just reproduce this same
 * `TopAppBar`/`IconButton` pair one indirection away.
 *
 * The `TopAppBar`'s default colors are left as-is rather than overridden to match the surrounding
 * `surfaceContainerLow` — same reasoning as [KeryxAlertDialog]'s Android `actual` ignoring
 * `containerColor`: M3's own defaults are what reads as native chrome here, not desktop's flat
 * surface pattern. The list rows are transparent instead (as `KeryxSettingRow`'s Android `actual`
 * is), so they take the screen's own tone rather than showing as bands of `surface`.
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
    selectedTabId: String?,
    onSelectTab: (String?) -> Unit,
    title: String?,
    content: @Composable (String) -> Unit,
) {
    val selectedTab = tabs.firstOrNull { it.id == selectedTabId }
    val goBack = { goBackInTabDialog(selectedTab?.id, onSelectTab, onDismissRequest) }
    Dialog(
        onDismissRequest = onDismissRequest,
        // decorFitsSystemWindows = false makes the dialog window itself edge-to-edge; leaving it at
        // its default (true) would have the window inset its own content by the system bars,
        // contradicting the safeDrawingPadding() below and leaving unpainted strips behind them.
        // dismissOnBackPress = false: the system back is the BackHandler below (see the KDoc).
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
        ),
    ) {
        BackHandler { goBack() }
        // This Dialog is a window of its own, so the Activity window's system-bar appearance
        // (set by ProvidePlatformInteraction) does not carry over — apply the same one here.
        SyncSystemBarAppearance(LocalAppDarkTheme.current)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                val backLabel = stringResource(Res.string.common_back)
                val barTitle = selectedTab?.label ?: title
                TopAppBar(
                    title = { if (barTitle != null) Text(barTitle) },
                    navigationIcon = {
                        IconButton(onClick = goBack) {
                            KeryxIcon(KeryxIcons.ArrowBack, contentDescription = backLabel)
                        }
                    },
                )
                if (selectedTab == null) {
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        tabs.forEach { tab ->
                            ListItem(
                                headlineContent = { Text(tab.label) },
                                leadingContent = { KeryxIcon(tab.icon, contentDescription = null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.fillMaxWidth().clickable { onSelectTab(tab.id) },
                            )
                        }
                    }
                } else {
                    // Keyed on the tab so each category's detail gets its own scroll position.
                    key(selectedTab.id) {
                        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            content(selectedTab.id)
                        }
                    }
                }
            }
        }
    }
}
