package works.merc.keryx.app.ui.home

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.domain.NotificationCenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Android's foreground alert Snackbar: the active half of the fix for alerts being announced only
 * by a badge on a pane the user may not even be looking at.
 *
 * Rendered here on desktop purely because this is where this repo hosts Compose UI tests; in
 * production desktop passes a `null` host and this composable does nothing (covered below).
 */
@OptIn(ExperimentalTestApi::class)
class ForegroundAlertSnackbarTest {

    private fun notification(
        id: String,
        level: AppNotificationLevel = AppNotificationLevel.ERROR,
        message: String = "msg:$id",
        action: AppNotificationAction? = null,
    ) = AppNotification(id = id, level = level, message = message, timestampMillis = 0L, action = action)

    @Test
    fun anAlertRaisedWhileTheWindowIsFocusedIsAnnounced() = runDesktopComposeUiTest {
        val center = NotificationCenter()
        val vm = NotificationCenterViewModel(center)
        val hostState = SnackbarHostState()
        setContent { ForegroundAlertSnackbar(vm, hostState, windowFocused = true) }
        waitForIdle()
        assertNull(hostState.currentSnackbarData)

        center.add(notification("a", message = "同期に失敗しました"))
        waitUntil { hostState.currentSnackbarData != null }

        assertEquals("同期に失敗しました", hostState.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun anInfoNotificationIsLeftToTheBellsBadge() = runDesktopComposeUiTest {
        // A new-version notice or a finished OPML import is not an alert.
        val center = NotificationCenter()
        val vm = NotificationCenterViewModel(center)
        val hostState = SnackbarHostState()
        setContent { ForegroundAlertSnackbar(vm, hostState, windowFocused = true) }
        waitForIdle()

        center.add(notification("a", level = AppNotificationLevel.INFO))
        repeat(5) { waitForIdle() }

        assertNull(hostState.currentSnackbarData)
    }

    @Test
    fun anAlertWaitsWhileTheWindowIsUnfocusedAndIsAnnouncedOnceItIsFocusedAgain() = runDesktopComposeUiTest {
        // The app backgrounded, the notification shade pulled down, or the settings dialog open:
        // announcing there would time the Snackbar out unseen and burn the alert for good.
        val center = NotificationCenter()
        val vm = NotificationCenterViewModel(center)
        val hostState = SnackbarHostState()
        var focused by mutableStateOf(false)
        setContent { ForegroundAlertSnackbar(vm, hostState, windowFocused = focused) }
        waitForIdle()

        center.add(notification("a"))
        repeat(5) { waitForIdle() }
        assertNull(hostState.currentSnackbarData)

        focused = true
        waitUntil { hostState.currentSnackbarData != null }

        assertEquals("msg:a", hostState.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun onlyTheNewestOfSeveralSimultaneousAlertsIsAnnounced() = runDesktopComposeUiTest {
        // Material 3 shows one Snackbar at a time, and the bell's badge already carries the count.
        val center = NotificationCenter()
        val vm = NotificationCenterViewModel(center)
        val hostState = SnackbarHostState()
        setContent { ForegroundAlertSnackbar(vm, hostState, windowFocused = true) }
        waitForIdle()

        center.add(notification("a"))
        center.add(notification("b"))
        waitUntil { hostState.currentSnackbarData != null }

        assertEquals("msg:b", hostState.currentSnackbarData?.visuals?.message)
        // The older one is consumed alongside it rather than queued up behind — dismissing the
        // shown Snackbar must not walk backwards through the queue.
        hostState.currentSnackbarData?.dismiss()
        repeat(5) { waitForIdle() }
        assertNull(hostState.currentSnackbarData)
    }

    @Test
    fun aRecurringFailureIsNotReAnnouncedEveryTimeItRecurs() = runDesktopComposeUiTest {
        // SyncRepository coalesces its errors, which mints a fresh id on every attempt — keying the
        // already-announced bookkeeping on the id would Snackbar every background sync.
        val center = NotificationCenter()
        val vm = NotificationCenterViewModel(center)
        val hostState = SnackbarHostState()
        setContent { ForegroundAlertSnackbar(vm, hostState, windowFocused = true) }
        waitForIdle()

        center.addCoalescing(notification("first", message = "同期に失敗しました"))
        waitUntil { hostState.currentSnackbarData != null }
        hostState.currentSnackbarData?.dismiss()
        waitForIdle()

        center.addCoalescing(notification("second", message = "同期に失敗しました"))
        repeat(5) { waitForIdle() }

        assertNull(hostState.currentSnackbarData)
    }

    @Test
    fun aDifferentAlertStillGetsAnnouncedAfterAnEarlierOneWasHandled() = runDesktopComposeUiTest {
        val center = NotificationCenter()
        val vm = NotificationCenterViewModel(center)
        val hostState = SnackbarHostState()
        setContent { ForegroundAlertSnackbar(vm, hostState, windowFocused = true) }
        waitForIdle()

        center.add(notification("a", message = "同期に失敗しました"))
        waitUntil { hostState.currentSnackbarData != null }
        hostState.currentSnackbarData?.dismiss()
        waitForIdle()

        center.add(notification("b", message = "フィードが見つかりません"))
        waitUntil { hostState.currentSnackbarData != null }

        assertEquals("フィードが見つかりません", hostState.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun tappingTheActionHandsTheNotificationToTheHost() = runDesktopComposeUiTest {
        val center = NotificationCenter()
        val vm = NotificationCenterViewModel(center)
        val hostState = SnackbarHostState()
        setContent { ForegroundAlertSnackbar(vm, hostState, windowFocused = true) }
        waitForIdle()

        center.add(notification("a", action = AppNotificationAction.ShowSettingsTab("cloud_sync")))
        waitUntil { hostState.currentSnackbarData != null }
        assertEquals("表示", hostState.currentSnackbarData?.visuals?.actionLabel)

        hostState.currentSnackbarData?.performAction()
        waitForIdle()

        assertEquals("a", vm.pendingAction?.id)
    }

    @Test
    fun theDestructiveResetActionIsAnnouncedWithoutAnActionButton() = runDesktopComposeUiTest {
        // Archiving and recreating the cloud database must go through its own confirmation in the
        // notification center, never a one-tap Snackbar.
        val center = NotificationCenter()
        val vm = NotificationCenterViewModel(center)
        val hostState = SnackbarHostState()
        setContent { ForegroundAlertSnackbar(vm, hostState, windowFocused = true) }
        waitForIdle()

        center.add(notification("a", action = AppNotificationAction.ResetCloudData))
        waitUntil { hostState.currentSnackbarData != null }

        assertEquals("msg:a", hostState.currentSnackbarData?.visuals?.message)
        assertNull(hostState.currentSnackbarData?.visuals?.actionLabel)
    }

    @Test
    fun aNullHostIsANoOpRatherThanACrash() = runDesktopComposeUiTest {
        // Desktop's own steady state: no in-app snackbar convention, so no host is ever created.
        val center = NotificationCenter()
        val vm = NotificationCenterViewModel(center)
        setContent { ForegroundAlertSnackbar(vm, hostState = null, windowFocused = true) }
        waitForIdle()

        center.add(notification("a"))
        waitForIdle()
    }
}
