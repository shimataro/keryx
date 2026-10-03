package works.merc.keryx.app.ui.common

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.resources.DrawableResource

/**
 * Drop-in replacement for `androidx.compose.material3.AlertDialog`. Desktop's `actual` renders in
 * a real, separate OS window (`DialogWindow`) instead of a Compose `Popup` — avoiding the Compose
 * Desktop heavyweight/lightweight interop bug where a native AWT panel (the article reader's
 * WebView) always paints on top of Popup-based dialogs in the same window — with `title` handed to
 * the native title bar (`DialogWindow(title = ...)`) as a plain string and, on macOS, redrawn in
 * the merged title-bar area, and `confirmText`/`onConfirm`/`confirmEnabled`/`dismissText` rendered
 * as real native Swing buttons. Android's `actual` is a plain M3 `AlertDialog` — none of the above
 * applies, since there is no heavyweight AWT panel that could paint over a Compose `Popup` there.
 * See each platform's own `KeryxDialogs.*.kt` for the details.
 *
 * Deliberately has no `modifier` parameter on either platform — the content lives in its own
 * dialog surface, not the caller's composition tree. Any trailing icon action next to the title
 * (e.g. "add folder"/"add tag") is a separate [titleAction] slot; when [dismissText] is non-null,
 * clicking it always just calls [onDismissRequest].
 *
 * @param destructive Marks [onConfirm] as a destructive action (deleting, unsubscribing, resetting
 *   cloud data). Android renders the confirm button in `colorScheme.error`, M3's convention for a
 *   destructive dialog action; desktop accepts it and keeps its native Swing button unchanged.
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
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    modal: Boolean = true,
    destructive: Boolean = false,
)

/** One tab (category) in a [KeryxTabDialog]: a stable [id] used for selection/dispatch, a localized
 * [label] (shown with the icon — under it in desktop's tab bar, beside it in Android's category
 * list — and as the selected tab's window title on macOS / detail-screen title on Android), and an
 * [icon]. */
data class KeryxDialogTab(val id: String, val label: String, val icon: DrawableResource)

/**
 * Renders the tab children for a Material3 [androidx.compose.material3.TabRow] or
 * [androidx.compose.material3.ScrollableTabRow]: the desktop [KeryxTabDialog] `actual`'s
 * `SecondaryScrollableTabRow`. Android's `actual` has no tab row — it lists the categories instead
 * (see its own KDoc).
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
 * A dialog with category navigation over a set of [KeryxDialogTab]s (see that class for what each
 * one carries), presented in each platform's own idiom. Desktop's is a modeless,
 * macOS-System-Preferences-style `DialogWindow` (see [KeryxAlertDialog] for why a real
 * `DialogWindow` rather than a Compose `Popup`) with a Material3 `SecondaryScrollableTabRow`/`Tab`
 * tab bar (rendered by [KeryxDialogTabs]) above the selected tab's content, whose label is mirrored
 * as the window title next to the traffic lights on macOS — the main window stays interactive while
 * it is open, matching the real macOS System Settings window. Android's is a modal, near-fullscreen
 * `Dialog` following Android's own settings pattern: a list of the categories, each opening its
 * content as a detail screen. See each platform's own `KeryxDialogs.*.kt` for the details.
 *
 * Has no button row: the caller's content applies its changes immediately. Desktop closes it via
 * the native close box or Escape. Android's back step — the `TopAppBar` back arrow and the system
 * back gesture/button alike, through [goBackInTabDialog] — returns from a category's detail to the
 * list, and closes the dialog from the list (the arrow exists because the near-fullscreen `Dialog`
 * leaves no tappable area outside its own content — see [KeryxTabDialog]'s Android `actual` for why
 * "outside tap" alone isn't a real dismiss path there).
 *
 * @param selectedTabId The selected tab's [KeryxDialogTab.id], or `null` for none: the dialog's
 *   default entry — Android's category list; desktop shows its first tab.
 * @param onSelectTab Called with the tab the user picked, or with `null` when Android's back step
 *   returns to the category list. Desktop's `actual` never passes `null`.
 * @param title The screen's own name. Rendered as the Android `actual`'s `TopAppBar` title on the
 *   category list; desktop's `actual` ignores it, since it already mirrors the selected tab's own
 *   label as the native window title instead (see that `actual`'s KDoc).
 * @param content receives the currently shown tab's [KeryxDialogTab.id] and renders that tab.
 */
@Composable
expect fun KeryxTabDialog(
    onDismissRequest: () -> Unit,
    tabs: List<KeryxDialogTab>,
    selectedTabId: String?,
    onSelectTab: (String?) -> Unit,
    title: String? = null,
    content: @Composable (String) -> Unit,
)

/**
 * The one back step of a [KeryxTabDialog] that has a category list (Android's): from a category's
 * detail ([selectedTabId] non-null) back to the list, and from the list out of the dialog. Every
 * back route — the `TopAppBar` arrow and the system back gesture/button — calls this, so they can
 * never disagree.
 */
internal fun goBackInTabDialog(
    selectedTabId: String?,
    onSelectTab: (String?) -> Unit,
    onDismissRequest: () -> Unit,
) {
    if (selectedTabId != null) onSelectTab(null) else onDismissRequest()
}
