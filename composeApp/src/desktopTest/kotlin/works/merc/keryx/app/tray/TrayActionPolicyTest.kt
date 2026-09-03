package works.merc.keryx.app.tray

import works.merc.keryx.app.core.UpdateException
import works.merc.keryx.app.core.UpdateStage
import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.domain.UpdateAsset
import works.merc.keryx.app.domain.UpdateAssetKind
import works.merc.keryx.app.domain.UpdatePlan
import works.merc.keryx.app.domain.UpdateState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val SOME_ASSET =
    UpdateAsset("Keryx-2.0.0-macos-arm64.zip", "https://x", 100L, "a".repeat(64), UpdateAssetKind.MAC_APP_ZIP)

private fun installableUpdate() =
    AvailableUpdate("2.0.0", "https://ex.com/2.0.0", null, SOME_ASSET, UpdatePlan.SelfReplace(SOME_ASSET))

private fun manualOnlyUpdate() =
    AvailableUpdate("2.0.0", "https://ex.com/2.0.0", null, null, UpdatePlan.OpenReleasePage)

class TrayActionPolicyTest {
    @Test
    fun `hides on a visible focused window with no recent notification`() {
        assertTrue(
            shouldHideOnTrayAction(
                windowVisible = true,
                windowFocused = true,
                nowMillis = 100_000L,
                lastNotificationSentAtMillis = 0L,
                recencyWindowMs = 5_000L,
            ),
        )
    }

    @Test
    fun `activates instead of hiding when a notification landed just inside the recency window`() {
        assertFalse(
            shouldHideOnTrayAction(
                windowVisible = true,
                windowFocused = true,
                nowMillis = 100_000L,
                lastNotificationSentAtMillis = 99_000L,
                recencyWindowMs = 5_000L,
            ),
        )
    }

    @Test
    fun `hides again once the notification is exactly at the recency boundary`() {
        assertTrue(
            shouldHideOnTrayAction(
                windowVisible = true,
                windowFocused = true,
                nowMillis = 100_000L,
                lastNotificationSentAtMillis = 95_000L,
                recencyWindowMs = 5_000L,
            ),
        )
    }

    @Test
    fun `hides when never notified even though the timestamp default is zero`() {
        assertTrue(
            shouldHideOnTrayAction(
                windowVisible = true,
                windowFocused = true,
                nowMillis = 1_000L,
                lastNotificationSentAtMillis = 0L,
                recencyWindowMs = 5_000L,
            ),
        )
    }

    @Test
    fun `never hides a window that is not visible, regardless of notification recency`() {
        assertFalse(
            shouldHideOnTrayAction(
                windowVisible = false,
                windowFocused = true,
                nowMillis = 100_000L,
                lastNotificationSentAtMillis = 0L,
                recencyWindowMs = 5_000L,
            ),
        )
    }

    @Test
    fun `never hides a visible window that is not focused, regardless of notification recency`() {
        assertFalse(
            shouldHideOnTrayAction(
                windowVisible = true,
                windowFocused = false,
                nowMillis = 100_000L,
                lastNotificationSentAtMillis = 0L,
                recencyWindowMs = 5_000L,
            ),
        )
    }

    // --- shouldOpenSettingsAfterUpdateCheck ---

    @Test
    fun `an installable update opens the settings dialog's updates tab`() {
        assertTrue(shouldOpenSettingsAfterUpdateCheck(UpdateState.Available(installableUpdate())))
    }

    /** Nothing on that tab to act on: the menu entry itself opens the release page instead. */
    @Test
    fun `a non-installable update does not open the settings dialog`() {
        assertFalse(shouldOpenSettingsAfterUpdateCheck(UpdateState.Available(manualOnlyUpdate())))
    }

    @Test
    fun `every other state leaves the settings dialog closed`() {
        listOf(
            UpdateState.Idle,
            UpdateState.Checking,
            UpdateState.UpToDate,
            UpdateState.Downloading(installableUpdate(), 1, 2),
            UpdateState.Verifying(installableUpdate()),
            UpdateState.Ready(installableUpdate(), "/tmp/x.zip"),
            UpdateState.Installing(installableUpdate()),
            UpdateState.Failed(null, UpdateException(UpdateStage.CHECK, "no network")),
        ).forEach { state ->
            assertFalse(shouldOpenSettingsAfterUpdateCheck(state), state.toString())
        }
    }
}
