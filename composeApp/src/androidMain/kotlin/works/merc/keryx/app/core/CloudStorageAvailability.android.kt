package works.merc.keryx.app.core

import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import works.merc.keryx.app.BuildConfig
import works.merc.keryx.app.platform.AndroidAppContext

/**
 * Android's cloud-backend availability. Dropbox and OneDrive use the same PKCE public-client
 * configuration as desktop (see `di/PlatformModule.android.kt`), so their availability reads the
 * same `BuildConfig` keys — `BuildConfig` is generated into `jvmCommonMain` (see
 * `composeApp/build.gradle.kts`'s `generatedBuildConfigDir` wiring), so it is visible here too.
 *
 * Google Drive is the exception: it is gated on **the device**, not on a build-time key. Google's
 * OAuth policy leaves no redirect-based flow for an Android client type, so this platform reaches
 * Drive through Play services' `AuthorizationClient` instead (`data/cloud/PlayServicesGoogleDriveAuth.kt`),
 * which identifies the app by package name + signing certificate rather than by a client id this
 * build would have to carry. A device without Play services — a de-Googled ROM — therefore cannot
 * offer Google Drive at all, and simply never sees it in the provider list; Dropbox, OneDrive and
 * local-only are unaffected. See `docs/sync-architecture.md`'s "Google Drive on Android".
 *
 * Deliberately **not** keyed on the distribution flavor (`github` / `play`). A play-flavored APK
 * can be sideloaded outside Play and a github-flavored one runs perfectly well on a device that has
 * Play services, so the flavor answers the wrong question — the same reasoning
 * `AndroidUpdateInstaller` applies to `REQUEST_INSTALL_PACKAGES`.
 */
actual object CloudStorageAvailability {
    actual val dropboxAvailable: Boolean = BuildConfig.DROPBOX_APP_KEY.isNotEmpty()

    /**
     * Evaluated once per process, like `platform/SelfUpdateCheck.android.kt`'s
     * `selfUpdateCheckSupported`: whether Play services is installed cannot change under a running
     * process in any way worth re-querying on every provider-list read. `by lazy` also keeps
     * `AndroidAppContext.application` from being read during class initialization, which can run
     * before `KeryxApplication.onCreate` has set it.
     *
     * Only [ConnectionResult.SUCCESS] counts — a device whose Play services needs an update or is
     * disabled cannot serve an authorization request, so offering Drive there would just fail later.
     */
    actual val googleDriveAvailable: Boolean by lazy {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(AndroidAppContext.application) == ConnectionResult.SUCCESS
    }

    // OneDrive is a PKCE public client — the client id alone gates availability (no secret).
    actual val oneDriveAvailable: Boolean = BuildConfig.ONEDRIVE_CLIENT_ID.isNotEmpty()

    /** `by lazy` for the same reason [googleDriveAvailable] is: reading it here eagerly would force that one. */
    actual val available: List<CloudStorageType> by lazy {
        availableCloudStorageTypes(dropbox = dropboxAvailable, googleDrive = googleDriveAvailable, oneDrive = oneDriveAvailable)
    }
}
