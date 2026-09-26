package works.merc.keryx.app.core

import works.merc.keryx.app.BuildConfig

/**
 * Apple builds offer Dropbox and OneDrive (PKCE public clients over the same `keryx://` redirect).
 * Google Drive stays unavailable until an iOS/macOS-type OAuth client (no client secret) is
 * registered for it — the desktop "Desktop app" client and its secret are not reused here; see
 * `docs/sync-architecture.md`.
 */
actual object CloudStorageAvailability {
    actual val dropboxAvailable: Boolean = BuildConfig.DROPBOX_APP_KEY.isNotEmpty()
    actual val googleDriveAvailable: Boolean = false
    actual val oneDriveAvailable: Boolean = BuildConfig.ONEDRIVE_CLIENT_ID.isNotEmpty()

    actual val available: List<CloudStorageType> =
        availableCloudStorageTypes(dropbox = dropboxAvailable, googleDrive = googleDriveAvailable, oneDrive = oneDriveAvailable)
}
