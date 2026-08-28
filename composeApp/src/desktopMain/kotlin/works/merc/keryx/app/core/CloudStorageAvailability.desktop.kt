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

    // Declaration order of CloudStorageType drives the UI display order.
    actual val available: List<CloudStorageType> = CloudStorageType.entries.filter {
        when (it) {
            CloudStorageType.DROPBOX -> dropboxAvailable
            CloudStorageType.GOOGLE_DRIVE -> googleDriveAvailable
            CloudStorageType.ONEDRIVE -> oneDriveAvailable
        }
    }
}
