package works.merc.keryx.app

import works.merc.keryx.app.core.UpdateException
import works.merc.keryx.app.core.UpdateStage
import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.domain.UpdateAsset
import works.merc.keryx.app.domain.UpdateAssetKind
import works.merc.keryx.app.domain.UpdatePlan
import works.merc.keryx.app.domain.UpdateState
import works.merc.keryx.app.ui.navigation.SettingsOpenRequest
import works.merc.keryx.app.ui.navigation.SettingsOpenRequests
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers `main.kt`'s [onUpdateMenuItemClicked] — the one click handler behind both the system
 * tray's and the Help menu's single update entry. Every state with something to do must land on
 * the Updates tab (via the [SettingsOpenRequests] router, raising the window through
 * [activationRequests]) and run exactly one of the two actions; the in-flight states must do
 * nothing at all. Which action a state maps to is `TrayActionPolicyTest`'s `updateMenuAction`
 * coverage; this suite checks the handler wires that decision to navigation and the action.
 */
class UpdateMenuActionTest {
    private var settingsOpenRequests = SettingsOpenRequests()
    private var checks = 0
    private var primaryActions = 0

    @BeforeTest
    fun setUp() {
        // activationRequests is a process-wide replay = 1 flow; start each test from an empty cache.
        activationRequests.resetReplayCache()
    }

    private fun click(state: UpdateState) =
        onUpdateMenuItemClicked(state, settingsOpenRequests, checkForUpdate = { checks++ }, performPrimaryAction = { primaryActions++ })

    private fun assertOpenedUpdatesTab() {
        assertEquals(SettingsOpenRequest("updates"), settingsOpenRequests.pending.value)
        assertEquals(listOf(Unit), activationRequests.replayCache, "the window must be brought to front")
    }

    private fun update(installable: Boolean): AvailableUpdate {
        val asset = UpdateAsset("Keryx-2.0.0-macos-arm64.zip", "https://x", 100L, "a".repeat(64), UpdateAssetKind.MAC_APP_ZIP)
        return if (installable) {
            AvailableUpdate("2.0.0", "https://ex.com/2.0.0", null, asset, UpdatePlan.SelfReplace(asset))
        } else {
            AvailableUpdate("2.0.0", "https://ex.com/2.0.0", null, null, UpdatePlan.OpenReleasePage)
        }
    }

    @Test
    fun idleAndUpToDateOpenTheUpdatesTabAndRunACheck() {
        listOf(UpdateState.Idle, UpdateState.UpToDate).forEach { state ->
            settingsOpenRequests = SettingsOpenRequests()
            activationRequests.resetReplayCache()
            checks = 0

            click(state)

            assertOpenedUpdatesTab()
            assertEquals(1, checks, state.toString())
        }
        assertEquals(0, primaryActions)
    }

    /** Nothing downloadable here: the tab links the release page, and the check refreshes it. */
    @Test
    fun aNonInstallableUpdateOpensTheUpdatesTabAndRunsACheckInsteadOfTheBrowser() {
        click(UpdateState.Available(update(installable = false)))

        assertOpenedUpdatesTab()
        assertEquals(1, checks)
        assertEquals(0, primaryActions)
    }

    @Test
    fun anInstallableUpdateAFailureAndAReadyDownloadOpenTheUpdatesTabAndRunThePrimaryAction() {
        listOf(
            UpdateState.Available(update(installable = true)),
            UpdateState.Failed(null, UpdateException(UpdateStage.CHECK, "no network")),
            UpdateState.Ready(update(installable = true), "/tmp/x.zip"),
        ).forEach { state ->
            settingsOpenRequests = SettingsOpenRequests()
            activationRequests.resetReplayCache()
            primaryActions = 0

            click(state)

            assertOpenedUpdatesTab()
            assertEquals(1, primaryActions, state.toString())
        }
        assertEquals(0, checks)
    }

    @Test
    fun theInFlightStatesDoNothingAtAll() {
        val update = update(installable = true)
        listOf(
            UpdateState.Checking,
            UpdateState.Downloading(update, 1, 2),
            UpdateState.Verifying(update),
            UpdateState.Installing(update),
        ).forEach { click(it) }

        assertNull(settingsOpenRequests.pending.value, "no navigation for an action already in flight")
        assertTrue(activationRequests.replayCache.isEmpty())
        assertEquals(0, checks)
        assertEquals(0, primaryActions)
    }
}
