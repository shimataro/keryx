package works.merc.keryx.app.ui.common

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.resources.DrawableResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Drop-in replacement for `androidx.compose.material3.AlertDialog` that renders in a real,
 * separate OS window (`DialogWindow`) instead of a Compose `Popup`. This avoids the Compose
 * Desktop heavyweight/lightweight interop bug where a native AWT panel (the article reader's
 * WebView) always paints on top of Popup-based dialogs in the same window.
 *
 * Deliberately has no `modifier` parameter — the content lives in its own window, not in the
 * caller's composition tree.
 *
 * `title` is a plain string (rather than a composable slot) so it can be handed to the native
 * title bar (`DialogWindow(title = ...)`) as well as, on macOS, redrawn in the merged title-bar
 * area. Any trailing icon action next to the title (e.g. "add folder"/"add tag") is a separate
 * [titleAction] slot. `confirmText`/`onConfirm`/`confirmEnabled`/`dismissText` replace the old
 * `confirmButton`/`dismissButton` composable slots so the buttons can be rendered as real native
 * Swing buttons; when [dismissText] is non-null, clicking it always just calls [onDismissRequest].
 */
@Composable
expect fun KeryxAlertDialog(
    onDismissRequest: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissText: String? = null,
    title: String? = null,
    titleAction: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
    tonalElevation: Dp = 0.dp,
    modal: Boolean = true,
)

/** One tab in a [KeryxTabDialog]: a stable [id] used for selection/dispatch, a localized [label]
 * (shown under the icon and, on macOS, mirrored as the window title), and an [icon]. */
data class KeryxDialogTab(val id: String, val label: String, val icon: DrawableResource)

/**
 * Renders the tab children for a Material3 [androidx.compose.material3.TabRow] or
 * [androidx.compose.material3.ScrollableTabRow] in both Android and Desktop `actual`s.
 *
 * Kept in [commonMain] so the icon/label rendering and truncation behavior stay identical across
 * platforms; only the surrounding container (`PrimaryScrollableTabRow` on Android,
 * `SecondaryScrollableTabRow` on Desktop) differs.
 */
@Composable
internal fun KeryxDialogTabs(
    tabs: List<KeryxDialogTab>,
    selectedTabId: String,
    onSelectTab: (String) -> Unit,
    selectedContentColor: Color = LocalContentColor.current,
    unselectedContentColor: Color = LocalContentColor.current,
) {
    tabs.forEach { tab ->
        Tab(
            selected = tab.id == selectedTabId,
            onClick = { onSelectTab(tab.id) },
            text = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            icon = { KeryxIcon(tab.icon, contentDescription = null) },
            selectedContentColor = selectedContentColor,
            unselectedContentColor = unselectedContentColor,
        )
    }
}

/**
 * A dialog with tab-based navigation: a row of tabs up top and a content area below that shows
 * whichever tab is currently selected — see [KeryxDialogTab] for what each tab carries. The two
 * `actual`s differ in how "native" is expressed here, not just in tab-bar style: desktop's is a
 * modeless, macOS-System-Preferences-style `DialogWindow` (see [KeryxAlertDialog] for why a real
 * `DialogWindow` rather than a Compose `Popup`) with a Material3 `SecondaryScrollableTabRow`/
 * `Tab` tab bar (rendered by [KeryxDialogTabs]), whose selected tab's label is mirrored as the
 * window title next to the traffic lights on macOS — the main window stays interactive while it
 * is open, matching the real macOS System Settings window. Android's is a modal, near-fullscreen
 * `Dialog` hosting a genuine M3 `PrimaryScrollableTabRow`/`Tab`. See each platform's own
 * `KeryxDialogs.*.kt` for the details.
 *
 * Has no button row: the caller's content applies its changes immediately. Desktop closes it via
 * the native close box or Escape; Android via the system back gesture/button or an outside tap.
 *
 * @param content receives the currently selected tab's [KeryxDialogTab.id] and renders that tab.
 */
@Composable
expect fun KeryxTabDialog(
    onDismissRequest: () -> Unit,
    tabs: List<KeryxDialogTab>,
    selectedTabId: String,
    onSelectTab: (String) -> Unit,
    content: @Composable (String) -> Unit,
)
