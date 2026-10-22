package works.merc.keryx.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Remembers that the app itself just sent the user to another app (a browser tab, a mail composer),
 * so the foreground exit that follows is not mistaken for the user leaving: it must not flush
 * deferred syncs (`SyncRepository.flushOnLeavingForeground`), or reading an article in the browser
 * would upload the whole database once per article — the very cost the deferred window avoids.
 *
 * Set by the platform [BrowserOpener] just before it launches, consumed by the exit handler, and
 * cleared whenever the app is back in front so a launch that never left the app (a dismissed chooser,
 * a failed launch) cannot swallow a later real exit. The flag is only ever read and written, never
 * collected; `MutableStateFlow` is the thread-safe holder commonMain offers.
 */
object ExternalAppLaunch {
    private val launched = MutableStateFlow(false)

    fun markLaunched() {
        launched.value = true
    }

    /** True if a launch was marked since the last [clear] / consume; resets the mark. */
    fun consumeLaunched(): Boolean = launched.getAndUpdate { false }

    fun clear() {
        launched.value = false
    }
}
