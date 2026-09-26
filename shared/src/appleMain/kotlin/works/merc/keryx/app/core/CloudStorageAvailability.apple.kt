package works.merc.keryx.app.core

import works.merc.keryx.app.AppleBuildConfig
import works.merc.keryx.app.BuildConfig

/**
 * Apple builds offer Dropbox and OneDrive (PKCE public clients over the same `keryx://` redirect),
 * plus Google Drive once an "iOS"-type OAuth client (no client secret) has been registered for it
 * and its id supplied at build time — the desktop "Desktop app" client and its secret are a
 * separate client and are never reused here. See `docs/sync-architecture.md`'s "Google Drive on
 * Apple".
 */
actual object CloudStorageAvailability {
    actual val dropboxAvailable: Boolean = BuildConfig.DROPBOX_APP_KEY.isNotEmpty()
    actual val googleDriveAvailable: Boolean = AppleBuildConfig.GOOGLE_DRIVE_CLIENT_ID.isNotEmpty()
    actual val oneDriveAvailable: Boolean = BuildConfig.ONEDRIVE_CLIENT_ID.isNotEmpty()

    actual val available: List<CloudStorageType> =
        availableCloudStorageTypes(dropbox = dropboxAvailable, googleDrive = googleDriveAvailable, oneDrive = oneDriveAvailable)
}
