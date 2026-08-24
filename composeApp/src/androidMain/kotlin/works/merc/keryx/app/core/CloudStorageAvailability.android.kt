package works.merc.keryx.app.core

import works.merc.keryx.app.BuildConfig

/**
 * Android's cloud-backend availability. Dropbox and OneDrive use the same PKCE public-client
 * configuration as desktop (see `di/PlatformModule.android.kt`), so their availability reads the
 * same `BuildConfig` keys — `BuildConfig` is generated into `jvmCommonMain` (see
 * `composeApp/build.gradle.kts`'s `generatedBuildConfigDir` wiring), so it is visible here too.
 *
 * Google Drive is fixed `false` on Android: Google's OAuth policy does not allow the desktop
 * "Desktop app" client's loopback-redirect + `client_secret` flow to be reused on Android
 * (loopback redirects are deprecated for the Android client type), and the platform's own
 * recommended replacement (Play services `AuthorizationClient`) requires a server-side
 * `client_secret` exchange to obtain a refresh token — both a new Play-services runtime
 * dependency and an APK-embedded secret this app does not want to take on casually. See
 * `docs/sync-architecture.md` ("Cloud Authentication") for the full investigation; this is
 * deliberately out of scope for the phase that added Dropbox/OneDrive to Android, not an
 * oversight.
 */
actual object CloudStorageAvailability {
    actual val dropboxAvailable: Boolean = BuildConfig.DROPBOX_APP_KEY.isNotEmpty()
    actual val googleDriveAvailable: Boolean = false

    // OneDrive is a PKCE public client — the client id alone gates availability (no secret).
    actual val oneDriveAvailable: Boolean = BuildConfig.ONEDRIVE_CLIENT_ID.isNotEmpty()

    // Declaration order of CloudStorageType drives the UI display order.
    actual val available: List<CloudStorageType> = CloudStorageType.entries.filter {
        when (it) {
            CloudStorageType.DROPBOX -> dropboxAvailable
            CloudStorageType.GOOGLE_DRIVE -> googleDriveAvailable
            CloudStorageType.ONEDRIVE -> oneDriveAvailable
        }
    }
}
