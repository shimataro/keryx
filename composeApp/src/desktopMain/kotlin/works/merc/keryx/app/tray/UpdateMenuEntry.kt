package works.merc.keryx.app.tray

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.domain.UpdateState
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.tray_update_download
import works.merc.keryx.app.resources.tray_update_downloading
import works.merc.keryx.app.resources.tray_update_failed
import works.merc.keryx.app.resources.tray_update_restart
import works.merc.keryx.app.resources.tray_update_verifying
import works.merc.keryx.app.resources.update_available_manual_only
import works.merc.keryx.app.resources.update_check_for_update
import works.merc.keryx.app.resources.update_checking
import works.merc.keryx.app.resources.update_installing
import works.merc.keryx.app.resources.update_up_to_date

/**
 * Maps [state] to the single update menu entry shown in **both** the system tray menu (see
 * [KeryxTray]) and the application menu bar's Help menu (`ui/AppMenuBar.kt`) — one function so the
 * two surfaces can never drift apart, and so `strings.xml` carries one set of labels rather than
 * two. `UpdatesTab.kt`'s button-state table is the same state machine rendered in the settings
 * dialog instead.
 *
 * The entry is **always present**: every [UpdateState] maps to a label, so the menus have a fixed
 * shape and the user always has a way to ask for a check (`Idle`/`UpToDate` are the "check for
 * updates" affordance). States with nothing to act on right now — a check, a download, a
 * verification or an install already in flight, i.e. [updateMenuAction] is [UpdateMenuAction.None]
 * — are shown disabled rather than removed, so the item does not appear and disappear underneath a
 * menu the user is looking at.
 *
 * Lives in its own file rather than in `TrayMenuModel.kt`, which is deliberately `@Composable`-free
 * (pure functions only); [roundedTrayProgressPercent] is reached from there without an import,
 * being in this same package.
 *
 * [settingsReachable] is `false` while the settings dialog cannot be opened (first-run Setup is
 * showing — see `SettingsOpenRequests`). Every state is then shown disabled, because whatever the
 * entry does it does on the settings dialog's Updates tab; the label still follows [state].
 *
 * See `main.kt`'s `onUpdateMenuItemClicked` for what a click on this entry does in each state.
 */
@Composable
internal fun updateMenuEntry(state: UpdateState, settingsReachable: Boolean): TrayUpdateEntry =
    TrayUpdateEntry(
        updateMenuLabel(state),
        // Enabled exactly when a click would do something: the same updateMenuAction the click
        // handler runs, so the label's affordance and the action can never disagree.
        enabled = settingsReachable && updateMenuAction(state) != UpdateMenuAction.None,
    )

@Composable
private fun updateMenuLabel(state: UpdateState): String = when (state) {
    UpdateState.Idle -> stringResource(Res.string.update_check_for_update)
    UpdateState.Checking -> stringResource(Res.string.update_checking)
    UpdateState.UpToDate -> stringResource(Res.string.update_up_to_date)
    is UpdateState.Available -> if (state.update.installable) {
        stringResource(Res.string.tray_update_download, state.update.version)
    } else {
        stringResource(Res.string.update_available_manual_only)
    }
    is UpdateState.Downloading -> {
        val percent = roundedTrayProgressPercent(state.bytesDone, state.bytesTotal)
        stringResource(Res.string.tray_update_downloading, "$percent%")
    }
    is UpdateState.Verifying -> stringResource(Res.string.tray_update_verifying)
    is UpdateState.Installing -> stringResource(Res.string.update_installing)
    is UpdateState.Ready -> stringResource(Res.string.tray_update_restart, state.update.version)
    is UpdateState.Failed -> stringResource(Res.string.tray_update_failed)
}
