package works.merc.keryx.app.tray

import works.merc.keryx.app.core.UpdateException
import works.merc.keryx.app.core.UpdateStage
import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.domain.UpdateAsset
import works.merc.keryx.app.domain.UpdateAssetKind
import works.merc.keryx.app.domain.UpdatePlan
import works.merc.keryx.app.domain.UpdateState
import kotlin.test.Test
import kotlin.test.assertEquals
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
                windowMinimized = false,
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
                windowMinimized = false,
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
                windowMinimized = false,
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
                windowMinimized = false,
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
                windowMinimized = false,
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
                windowMinimized = false,
                windowFocused = false,
                nowMillis = 100_000L,
                lastNotificationSentAtMillis = 0L,
                recencyWindowMs = 5_000L,
            ),
        )
    }

    // --- trayWindowShown / minimized windows ---

    @Test
    fun `a minimized window is not shown, so the tray offers Show for it`() {
        assertTrue(trayWindowShown(windowVisible = true, windowMinimized = false))
        assertFalse(trayWindowShown(windowVisible = true, windowMinimized = true))
        assertFalse(trayWindowShown(windowVisible = false, windowMinimized = false))
        assertFalse(trayWindowShown(windowVisible = false, windowMinimized = true))
    }

    /** Only a visible, un-minimized, focused window is hidden by an icon click; all else activates. */
    @Test
    fun `an icon click hides only a shown and focused window, across the whole matrix`() {
        for (visible in listOf(true, false)) for (minimized in listOf(true, false)) for (focused in listOf(true, false)) {
            val hides = shouldHideOnTrayAction(
                windowVisible = visible,
                windowMinimized = minimized,
                windowFocused = focused,
                nowMillis = 100_000L,
                lastNotificationSentAtMillis = 0L,
                recencyWindowMs = 5_000L,
            )
            assertEquals(visible && !minimized && focused, hides, "visible=$visible minimized=$minimized focused=$focused")
        }
    }

    @Test
    fun `a minimized window that still reports focus is activated, not hidden`() {
        assertFalse(
            shouldHideOnTrayAction(
                windowVisible = true,
                windowMinimized = true,
                windowFocused = true,
                nowMillis = 100_000L,
                lastNotificationSentAtMillis = 0L,
                recencyWindowMs = 5_000L,
            ),
        )
    }

    // --- updateMenuAction ---

    @Test
    fun `idle and up to date run a check`() {
        assertEquals(UpdateMenuAction.Check, updateMenuAction(UpdateState.Idle))
        assertEquals(UpdateMenuAction.Check, updateMenuAction(UpdateState.UpToDate))
    }

    /** Nothing to download here: the check refreshes it, and the Updates tab links the release page. */
    @Test
    fun `a non-installable update runs a check rather than opening the browser`() {
        assertEquals(UpdateMenuAction.Check, updateMenuAction(UpdateState.Available(manualOnlyUpdate())))
    }

    @Test
    fun `an installable update, a failure and a ready download run the primary action`() {
        assertEquals(UpdateMenuAction.Primary, updateMenuAction(UpdateState.Available(installableUpdate())))
        assertEquals(UpdateMenuAction.Primary, updateMenuAction(UpdateState.Failed(null, UpdateException(UpdateStage.CHECK, "no network"))))
        assertEquals(UpdateMenuAction.Primary, updateMenuAction(UpdateState.Ready(installableUpdate(), "/tmp/x.zip")))
    }

    @Test
    fun `in-flight states do nothing`() {
        listOf(
            UpdateState.Checking,
            UpdateState.Downloading(installableUpdate(), 1, 2),
            UpdateState.Verifying(installableUpdate()),
            UpdateState.Installing(installableUpdate()),
        ).forEach { state ->
            assertEquals(UpdateMenuAction.None, updateMenuAction(state), state.toString())
        }
    }
}
