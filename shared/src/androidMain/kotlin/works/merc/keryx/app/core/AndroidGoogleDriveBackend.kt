package works.merc.keryx.app.core

import android.content.Context
import io.ktor.client.HttpClient
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.domain.CloudSession

/**
 * Android's way of reaching Google Drive, supplied by the application rather than compiled into
 * `:shared`/`:composeApp`: it needs Google Play services, and the `fdroid` flavor must not contain
 * a single class of it (F-Droid's inclusion policy forbids Play services). `:androidGms` holds the
 * one implementation; `:androidApp`'s `github` and `play` flavors register it, `fdroid` registers
 * nothing — which is the same state as a device without Play services, so Google Drive is simply
 * never offered and no UI needs to know the difference.
 */
interface AndroidGoogleDriveBackend {
    /**
     * Whether this device can serve Google Drive right now. Only a fully usable Play services
     * counts: one that is disabled or needs an update cannot answer an authorization request, so
     * offering Drive there would only fail later.
     */
    fun isAvailable(context: Context): Boolean

    /** The [CloudSession.Provider] for Google Drive; only called when [isAvailable] was true. */
    fun provider(client: HttpClient, tokenStorage: TokenStorage): CloudSession.Provider
}

/**
 * Where the application hands over its [AndroidGoogleDriveBackend] — the same shape as
 * [works.merc.keryx.app.platform.AndroidAppContext.init], and called from the same place
 * (`KeryxApplication.onCreate`, before Koin starts). Never called ⇒ [backend] is `null` ⇒ Google
 * Drive is unavailable.
 */
object AndroidGoogleDriveSupport {
    var backend: AndroidGoogleDriveBackend? = null
        private set

    fun install(backend: AndroidGoogleDriveBackend) {
        check(this.backend == null) { "AndroidGoogleDriveBackend is already installed" }
        this.backend = backend
    }
}
