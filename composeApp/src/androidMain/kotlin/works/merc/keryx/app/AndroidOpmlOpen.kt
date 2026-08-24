package works.merc.keryx.app

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.domain.importOpmlAndNotify
import works.merc.keryx.app.platform.readTextFromUri

private const val LOG_TAG = "AndroidOpmlOpen"

/**
 * Imports an `.opml` file opened from another app (a file manager, a mail attachment, another
 * feed reader's export — see `AndroidManifest.xml`'s second `ACTION_VIEW` intent-filter on
 * `MainActivity`), the Android counterpart of desktop's `.opml` file association
 * (`handleOpenedOpmlFile` in `StartupTasks.kt`). Reads the incoming `content://` `Uri` via
 * [readTextFromUri] (the same helper `FilePicker.android.kt`'s SAF picker uses) rather than a
 * filesystem path, then delegates to the platform-independent [importOpmlAndNotify]. Unlike
 * desktop, there is no window to re-activate afterward — the incoming intent already brought this
 * Activity to the foreground.
 *
 * Public rather than `internal`: `MainActivity` lives in the separate `:androidApp` Gradle module,
 * which `internal`'s module-scoped visibility would put out of reach (same reason
 * `dispatchOAuthCallbackIfPresent` is public).
 *
 * @return `true` if [intent] looked like an OPML-open request and a read+import was launched
 * (regardless of whether it later succeeds), so the caller can decide whether to clear the
 * intent's data (avoiding reprocessing it on a later recreation, e.g. a screen rotation replaying
 * the same `Intent` — unlike a re-dispatched OAuth callback, a re-imported OPML file would
 * duplicate real data, so this caller-side clearing is required, not just a courtesy).
 */
fun handleOpmlOpenIfPresent(koin: Koin, intent: Intent?): Boolean {
    if (intent?.action != Intent.ACTION_VIEW) return false
    val uri = intent.data ?: return false
    // The keryx:// OAuth redirect shares MainActivity's ACTION_VIEW handling but is a distinct
    // intent-filter (distinct scheme) dispatched separately by dispatchOAuthCallbackIfPresent —
    // this function only claims what that one doesn't.
    if (uri.scheme == "keryx") return false

    koin.get<CoroutineScope>().launch {
        val xml = readTextFromUri(uri) ?: run {
            Log.warn(LOG_TAG, "Could not read the opened OPML file")
            return@launch
        }
        importOpmlAndNotify(koin, xml)
    }
    return true
}
