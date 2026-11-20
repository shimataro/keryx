package works.merc.keryx.app.domain

import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.core.infoDialogText
import works.merc.keryx.app.core.runCatchingUnlessCancelled
import works.merc.keryx.app.platform.localWriteFailureKind

/**
 * Reports a local DB write that failed off the caller's thread — the writes the UI hands off and
 * returns from at once: the feed list's actions (`SerialWriteQueue`), read/star changes
 * (`HomeViewModel`) and global settings (`SettingsRepository`).
 *
 * Such a failure has no caller left to throw to — thrown, it would end the app on Android and Apple
 * and vanish on desktop — so it is the documented exception to "a DB failure is an exception"
 * (`docs/error-design.md`, "Local write failures"): it is logged, classified
 * ([localWriteFailureKind]) and posted to the notification center as one coalesced error per kind,
 * whose next action explains the cause and what to do. Undoing what the UI showed ahead of the write
 * stays with the caller, which knows what that was.
 */
class LocalWriteFailureReporter(
    private val notificationCenter: NotificationCenter,
    private val clock: Clock,
) {
    /**
     * Logs [e] and posts (coalesced: a recurrence of the same kind replaces the earlier entry) the
     * notification for its [localWriteFailureKind].
     */
    fun report(e: Throwable) {
        val kind = e.localWriteFailureKind()
        Log.error(TAG, "A local write failed (kind=$kind)", e)
        notificationCenter.addCoalescing(
            newNotification(
                level = AppNotificationLevel.ERROR,
                text = NotificationText.LocalWriteFailed(kind),
                nowMillis = clock.nowMillis(),
                action = AppNotificationAction.ShowInfoDialog(kind.infoDialogText),
            ),
        )
    }

    /**
     * Runs [block], [report]ing anything it throws other than cancellation, which still propagates.
     *
     * @return `true` if [block] completed, `false` if it failed (and was reported).
     */
    suspend fun runLocalWrite(block: suspend () -> Unit): Boolean =
        runCatchingUnlessCancelled { block() }.fold(onSuccess = { true }, onFailure = { report(it); false })

    private companion object {
        const val TAG = "LocalWriteFailureReporter"
    }
}
