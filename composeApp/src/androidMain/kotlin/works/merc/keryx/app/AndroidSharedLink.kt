package works.merc.keryx.app

import android.content.Intent
import org.koin.core.Koin
import works.merc.keryx.app.presentation.home.extractSharedFeedUrl
import works.merc.keryx.app.ui.navigation.AddFeedRequests

/**
 * Accepts a link another app shared to Keryx (`ACTION_SEND` of `text/plain` — see
 * `AndroidManifest.xml`'s share intent-filter on `MainActivity`): the first http(s) URL in the
 * shared text ([extractSharedFeedUrl]) opens the Add feed dialog pre-filled with it, through
 * [AddFeedRequests] (held until Home is showing). Nothing is subscribed until the user confirms
 * there; text without an http(s) URL is ignored.
 *
 * An intent relaunched from Recents carries the original share again
 * (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`); it is claimed but not acted on, so reopening the app
 * later does not reopen the dialog for a link that was already handled.
 *
 * Public rather than `internal` for the same reason as [handleOpmlOpenIfPresent]: `MainActivity`
 * lives in the separate `:androidApp` module.
 *
 * @return `true` if [intent] was a share (whether or not it held a usable URL), so the caller clears
 *   it and a later recreation of the Activity (a rotation) does not replay it.
 */
fun handleSharedLinkIfPresent(koin: Koin, intent: Intent?): Boolean {
    if (intent?.action != Intent.ACTION_SEND) return false
    if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return true
    val url = extractSharedFeedUrl(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
    if (url != null) koin.get<AddFeedRequests>().request(url)
    return true
}
