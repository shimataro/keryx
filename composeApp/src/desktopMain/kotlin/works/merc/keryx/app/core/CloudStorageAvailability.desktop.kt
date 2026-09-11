package works.merc.keryx.app.core

import works.merc.keryx.app.BuildConfig
import works.merc.keryx.app.DesktopBuildConfig

actual object CloudStorageAvailability {
    actual val dropboxAvailable: Boolean = BuildConfig.DROPBOX_APP_KEY.isNotEmpty()
    actual val googleDriveAvailable: Boolean =
        DesktopBuildConfig.GOOGLE_DRIVE_CLIENT_ID.isNotEmpty() &&
            DesktopBuildConfig.GOOGLE_DRIVE_CLIENT_SECRET.isNotEmpty()

    // OneDrive is a PKCE public client — the client id alone gates availability (no secret).
    actual val oneDriveAvailable: Boolean = BuildConfig.ONEDRIVE_CLIENT_ID.isNotEmpty()

    actual val available: List<CloudStorageType> =
        availableCloudStorageTypes(dropbox = dropboxAvailable, googleDrive = googleDriveAvailable, oneDrive = oneDriveAvailable)
}
