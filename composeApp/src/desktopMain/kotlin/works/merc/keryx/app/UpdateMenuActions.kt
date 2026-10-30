package works.merc.keryx.app

import works.merc.keryx.app.core.SETTINGS_TAB_UPDATES
import works.merc.keryx.app.domain.UpdateRepository
import works.merc.keryx.app.domain.UpdateState
import works.merc.keryx.app.tray.UpdateMenuAction
import works.merc.keryx.app.tray.updateMenuAction
import works.merc.keryx.app.ui.navigation.SettingsOpenRequests

/**
 * Runs whatever the single update menu entry currently stands for — the one shown in the system
 * tray and, identically, in the application menu bar's Help menu (see `tray/UpdateMenuEntry.kt`'s
 * `updateMenuEntry`, which resolves the matching label and enabled flag from the same [state]).
 *
 * Whatever [updateMenuAction] maps [state] to — other than [UpdateMenuAction.None], an action
 * already in flight (the entry is disabled then; a click from a stale menu is a deliberate no-op) —
 * the window is brought to front and the settings dialog opened on its Updates tab through the
 * [SettingsOpenRequests] router every Settings route uses, and then the action runs:
 * [checkForUpdate] (meant to be `SettingsViewModel.checkForUpdate`, the same in-flight-guarded check
 * the tab's own "check now" button and auto-check-on-open use) or [performPrimaryAction]
 * (`UpdateRepository.performPrimaryAction`: download, per-stage retry, or install). The tab's inline
 * status is the entry's only feedback, so an up-to-date, failed or non-installable result reads the
 * same as it does after pressing "check now" there.
 *
 * [state] is passed in rather than read here so each call site can decide how fresh a value it
 * wants; both existing ones read `updateRepository.state.value` at click time.
 */
internal fun onUpdateMenuItemClicked(
    state: UpdateState,
    settingsOpenRequests: SettingsOpenRequests,
    checkForUpdate: () -> Unit,
    performPrimaryAction: () -> Unit,
) {
    val action = updateMenuAction(state)
    if (action == UpdateMenuAction.None) return
    activationRequests.tryEmit(Unit)
    settingsOpenRequests.request(SETTINGS_TAB_UPDATES)
    when (action) {
        UpdateMenuAction.Check -> checkForUpdate()
        UpdateMenuAction.Primary -> performPrimaryAction()
        UpdateMenuAction.None -> Unit
    }
}

/**
 * [onUpdateMenuItemClicked] over [updateRepository]'s state at click time, with its primary action —
 * the wiring the tray's and the menu bar's update entries share.
 */
internal fun onUpdateMenuItemClicked(
    updateRepository: UpdateRepository,
    settingsOpenRequests: SettingsOpenRequests,
    checkForUpdate: () -> Unit,
) = onUpdateMenuItemClicked(
    updateRepository.state.value,
    settingsOpenRequests,
    checkForUpdate,
    updateRepository::performPrimaryAction,
)
