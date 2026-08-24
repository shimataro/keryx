package works.merc.keryx.app.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.mp.KoinPlatform
import works.merc.keryx.app.App
import works.merc.keryx.app.dispatchOAuthCallbackIfPresent
import works.merc.keryx.app.handleOpmlOpenIfPresent
import works.merc.keryx.app.platform.AndroidFilePickerHost
import works.merc.keryx.app.runAndroidStartupTasks

/**
 * Android's single [ComponentActivity], equivalent to desktop's `Window { App() }` in `main.kt`.
 * All app-process-wide setup (Koin, the image loader, FTS backfill) already ran in
 * [KeryxApplication.onCreate] before this activity is ever created.
 */
class MainActivity : ComponentActivity() {
    // Registered as instance properties (evaluated at construction, before onCreate) rather than
    // inside onCreate itself — ActivityResultLauncher registration must happen before the Activity
    // reaches STARTED, and a property initializer is the standard way to guarantee that.
    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        AndroidFilePickerHost.onOpenResult(uri)
    }

    // The wildcard MIME type is deliberate, confirmed necessary on-device: CreateDocument's
    // underlying storage provider appends the extension it derives from the given MIME type
    // whenever the suggested name's own extension doesn't already match one registered for that
    // MIME — and `.opml` isn't registered against any standard MIME type (text/xml included), so
    // "keryx.opml" saved as "text/xml" came back "keryx.opml.xml" on a real device. "*/*" has no
    // derivable extension, so the provider leaves the suggested name (and its .opml extension)
    // alone.
    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        AndroidFilePickerHost.onCreateResult(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            App()
        }

        AndroidFilePickerHost.attach(openDocumentLauncher, createDocumentLauncher)

        val koin = KoinPlatform.getKoin()
        dispatchIncomingViewIntent(koin)

        // See runAndroidStartupTasks's own KDoc for why this runs from the Activity rather than
        // Application.onCreate (which also runs when WorkManager wakes the process for
        // FeedRefreshWorker) — and why it's safe to call again on every onCreate (rotation), since
        // the function guards itself to once per process.
        koin.get<CoroutineScope>().launch { runAndroidStartupTasks(koin) }
    }

    override fun onDestroy() {
        // A configuration change (rotation, etc.) destroys and recreates this Activity around an
        // in-flight SAF picker that is still running independently — see AndroidFilePickerHost's
        // own KDoc for why a still-pending request must survive that, not just permanent finish.
        AndroidFilePickerHost.detach(retainPending = isChangingConfigurations)
        super.onDestroy()
    }

    /**
     * `singleTask` (see `AndroidManifest.xml`) means a `keryx://` OAuth redirect or an `.opml`
     * "open with Keryx" reaches an already-running instance here rather than via a fresh
     * [onCreate].
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchIncomingViewIntent(KoinPlatform.getKoin())
    }

    /**
     * Forwards this Activity's current intent to [dispatchOAuthCallbackIfPresent] and
     * [handleOpmlOpenIfPresent] (the two `ACTION_VIEW` cases `AndroidManifest.xml` declares
     * intent-filters for), then clears the intent's data if either claimed it. The clear matters
     * because a screen rotation recreates the Activity with the *same* [getIntent] object (no new
     * [onNewIntent] call) — without clearing the data, a rotation right after handling one of
     * these would resubmit it. For the OAuth case that's harmless but pointless (nothing is still
     * listening for that `state` by then); for the OPML case it would re-import the same file and
     * duplicate real data, so this clearing is required rather than just a courtesy.
     */
    private fun dispatchIncomingViewIntent(koin: Koin) {
        val current = intent ?: return
        val handled = dispatchOAuthCallbackIfPresent(koin, current.data?.toString()) ||
            handleOpmlOpenIfPresent(koin, current)
        if (handled) current.data = null
    }
}
