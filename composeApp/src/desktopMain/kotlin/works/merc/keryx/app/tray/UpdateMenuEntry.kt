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
 * verification or an install already in flight — are shown disabled rather than removed, so the
 * item does not appear and disappear underneath a menu the user is looking at.
 *
 * Lives in its own file rather than in `TrayMenuModel.kt`, which is deliberately `@Composable`-free
 * (pure functions only); [roundedTrayProgressPercent] is reached from there without an import,
 * being in this same package.
 *
 * See `main.kt`'s `onUpdateMenuItemClicked` for what a click on this entry does in each state.
 */
@Composable
internal fun updateMenuEntry(state: UpdateState): TrayUpdateEntry = when (state) {
    UpdateState.Idle -> TrayUpdateEntry(stringResource(Res.string.update_check_for_update), enabled = true)
    UpdateState.Checking -> TrayUpdateEntry(stringResource(Res.string.update_checking), enabled = false)
    UpdateState.UpToDate -> TrayUpdateEntry(stringResource(Res.string.update_up_to_date), enabled = true)
    is UpdateState.Available -> if (state.update.installable) {
        TrayUpdateEntry(stringResource(Res.string.tray_update_download, state.update.version), enabled = true)
    } else {
        TrayUpdateEntry(stringResource(Res.string.update_available_manual_only), enabled = true)
    }
    is UpdateState.Downloading -> {
        val percent = roundedTrayProgressPercent(state.bytesDone, state.bytesTotal)
        TrayUpdateEntry(stringResource(Res.string.tray_update_downloading, "$percent%"), enabled = false)
    }
    is UpdateState.Verifying -> TrayUpdateEntry(stringResource(Res.string.tray_update_verifying), enabled = false)
    is UpdateState.Installing -> TrayUpdateEntry(stringResource(Res.string.update_installing), enabled = false)
    is UpdateState.Ready -> TrayUpdateEntry(stringResource(Res.string.tray_update_restart, state.update.version), enabled = true)
    is UpdateState.Failed -> TrayUpdateEntry(stringResource(Res.string.tray_update_failed), enabled = true)
}
