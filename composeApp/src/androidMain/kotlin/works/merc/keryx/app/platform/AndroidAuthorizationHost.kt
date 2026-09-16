package works.merc.keryx.app.platform

import android.app.PendingIntent
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges `MainActivity`'s `StartIntentSenderForResult` launcher to the Google Drive connect flow
 * (`data/cloud/PlayServicesGoogleDriveAuth.kt`), which needs an Activity to run Play services'
 * consent screen: `AuthorizationClient.authorize()` answers with a [PendingIntent] whenever the
 * user has not granted the scope yet, and only an Activity can start it for a result.
 *
 * The same shape — and for the same reason — as [AndroidFilePickerHost]: `ActivityResultLauncher`
 * registration must happen during Activity initialization (before `STARTED`), but the caller is a
 * plain suspend function reached from a ViewModel, with no Activity reference of its own.
 *
 * A request made while no Activity is attached resolves to `null`, which the connect flow reports
 * as "not connected" rather than as a failure — that is the background case (`WorkManager` has no
 * Activity), where silently waiting for the next foreground sync is the right outcome.
 *
 * [attach]/[detach]/[onResult] are public rather than `internal`: `MainActivity` lives in the
 * separate `:androidApp` Gradle module, which `internal`'s module-scoped visibility would put out
 * of reach (the same reason [AndroidFilePickerHost]'s are).
 */
object AndroidAuthorizationHost {
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pending: CompletableDeferred<Intent?>? = null

    /** Called from `MainActivity.onCreate`, once the launcher is registered. */
    fun attach(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        this.launcher = launcher
    }

    /**
     * Called from `MainActivity.onDestroy`. See [AndroidFilePickerHost.detach] for why a pending
     * request survives a configuration change but not a permanent destruction.
     */
    fun detach(retainPending: Boolean) {
        launcher = null
        if (!retainPending) {
            pending?.complete(null)
            pending = null
        }
    }

    /** Called from `MainActivity`'s `StartIntentSenderForResult` `ActivityResultCallback`. */
    fun onResult(result: ActivityResult) {
        pending?.complete(result.data)
        pending = null
    }

    /**
     * Starts [pendingIntent] for a result and suspends until the consent screen returns, answering
     * with its result `Intent` (`null` when the user dismissed it, when no Activity is attached, or
     * when another request is already in flight — see [AndroidFilePickerHost.launchOpen] for why a
     * concurrent request is rejected rather than replacing the in-flight one).
     *
     * **The `finally` releases the slot only when [ActivityResultLauncher.launch] itself failed**,
     * because that is the one case where the request can never complete on its own. Cancelling the
     * waiting coroutine (`SettingsViewModel`/`SetupViewModel` do that when the user aborts the
     * connect dialog) does *not* cancel an already-launched consent Activity, so clearing [pending]
     * there would leave that still-live Activity's eventual result free to complete whichever
     * request happens to occupy the slot next — [onResult] has no request id to match on. After a
     * successful launch the slot is therefore released by [onResult] (a result always arrives, the
     * back key included, as `RESULT_CANCELED`) or by a permanent [detach].
     *
     * The cost is that a reconnect attempted between such a cancellation and the result arriving
     * hits the already-in-flight guard above and resolves to `null` — reported as a dismissed
     * authorization. That is the intended trade: far better than completing the new request with
     * the old request's result. Keeping [pending] across a configuration change is safe for the
     * reason [AndroidFilePickerHost.detach]'s KDoc gives — `ActivityResultRegistry` redelivers the
     * result to the recreated Activity's freshly re-registered launcher.
     */
    internal suspend fun launch(pendingIntent: PendingIntent): Intent? {
        val launcher = this.launcher ?: return null
        if (pending != null) return null
        val deferred = CompletableDeferred<Intent?>()
        pending = deferred
        var launched = false
        return try {
            launcher.launch(IntentSenderRequest.Builder(pendingIntent).build())
            launched = true
            deferred.await()
        } finally {
            if (!launched && pending === deferred) pending = null
        }
    }
}
