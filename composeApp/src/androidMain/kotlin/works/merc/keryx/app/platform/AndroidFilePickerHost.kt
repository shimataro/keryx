package works.merc.keryx.app.platform

import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges `MainActivity`'s `ActivityResultLauncher`s to [FilePicker], the commonMain `expect
 * object` `SettingsViewModel` calls through [FileSelector]. `ActivityResultLauncher` registration
 * must happen during Activity initialization (before it reaches `STARTED`), but [FilePicker] is a
 * plain object with no Activity reference and is called from a ViewModel, outside any composition
 * or Activity lifecycle — so `MainActivity` registers its launchers once and [attach]es them here,
 * and [FilePicker]'s actual just calls through this object instead of holding an Activity
 * reference itself. The same shape as `platform/NotificationPermission.android.kt`'s
 * launcher-holding pattern, but as a process-wide host rather than a `@Composable` remember, since
 * `FilePicker.pickOpenFile`/`pickSaveFile` are plain suspend functions, not composables.
 *
 * A picker request made while no Activity is attached (or while the previous request is still
 * pending — SAF only supports one outstanding request per launcher) resolves to `null`, the same
 * "the user cancelled" outcome [SettingsViewModel] already handles for a dismissed dialog.
 *
 * [attach]/[detach]/[onOpenResult]/[onCreateResult] are public rather than `internal`:
 * `MainActivity` lives in the separate `:androidApp` Gradle module, which `internal`'s
 * module-scoped visibility would put out of reach (the same reason `runAndroidStartupTasks` and
 * `dispatchOAuthCallbackIfPresent` are public).
 */
object AndroidFilePickerHost {
    private var openLauncher: ActivityResultLauncher<Array<String>>? = null
    private var createLauncher: ActivityResultLauncher<String>? = null
    private var pendingOpen: CompletableDeferred<Uri?>? = null
    private var pendingCreate: CompletableDeferred<Uri?>? = null

    /** Called from `MainActivity.onCreate`, once both launchers are registered. */
    fun attach(openLauncher: ActivityResultLauncher<Array<String>>, createLauncher: ActivityResultLauncher<String>) {
        this.openLauncher = openLauncher
        this.createLauncher = createLauncher
    }

    /**
     * Called from `MainActivity.onDestroy`. Always clears the launcher references (they belong to
     * the dying Activity instance either way), but only resolves a still-pending request to
     * `null` when [retainPending] is `false` — i.e. permanent destruction, not a configuration
     * change. A configuration change (e.g. rotation) destroys and recreates `MainActivity` around
     * an in-flight SAF picker that keeps running independently; `ActivityResultRegistry` is
     * designed to redeliver that pending result to the recreated Activity's freshly re-registered
     * launcher (`onOpenResult`/`onCreateResult`), so completing the deferred here would both tell
     * the original caller "cancelled" prematurely and leave nothing to complete when the real
     * result later arrives.
     *
     * @param retainPending `true` while the Activity is only being recreated for a configuration
     * change (`ComponentActivity.isChangingConfigurations`); `false` on permanent destruction,
     * where the request truly can never complete otherwise.
     */
    fun detach(retainPending: Boolean) {
        openLauncher = null
        createLauncher = null
        if (!retainPending) {
            pendingOpen?.complete(null)
            pendingOpen = null
            pendingCreate?.complete(null)
            pendingCreate = null
        }
    }

    /** Called from `MainActivity`'s `OpenDocument` `ActivityResultCallback`. */
    fun onOpenResult(uri: Uri?) {
        pendingOpen?.complete(uri)
        pendingOpen = null
    }

    /** Called from `MainActivity`'s `CreateDocument` `ActivityResultCallback`. */
    fun onCreateResult(uri: Uri?) {
        pendingCreate?.complete(uri)
        pendingCreate = null
    }

    /**
     * Launches the SAF "open document" picker for any MIME type — [OpenFileRequest.extensions]
     * cannot be honored here (SAF filters by MIME, and `.opml` has no widely registered MIME type;
     * see [FilePicker]'s own KDoc) — and suspends until the user picks a file or cancels.
     */
    internal suspend fun launchOpen(): Uri? {
        val launcher = openLauncher ?: return null
        pendingOpen?.complete(null)
        val deferred = CompletableDeferred<Uri?>()
        pendingOpen = deferred
        launcher.launch(arrayOf("*/*"))
        return deferred.await()
    }

    /** Launches the SAF "create document" picker defaulting to [defaultName]. */
    internal suspend fun launchCreate(defaultName: String): Uri? {
        val launcher = createLauncher ?: return null
        pendingCreate?.complete(null)
        val deferred = CompletableDeferred<Uri?>()
        pendingCreate = deferred
        launcher.launch(defaultName)
        return deferred.await()
    }
}
