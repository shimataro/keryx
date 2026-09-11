package works.merc.keryx.app.core

/**
 * Cloud storage backends the app can support. `id` is persisted in local settings.
 *
 * Declaration order is the UI display order (Dropbox first, Google Drive second) —
 * iterate [entries]/[CloudStorageAvailability.available] rather than relying on any
 * map iteration order for display.
 */
enum class CloudStorageType(val id: String) {
    DROPBOX("dropbox"),
    GOOGLE_DRIVE("google_drive"),
    ONEDRIVE("onedrive"),
    ;

    companion object {
        fun fromId(id: String?): CloudStorageType? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Which cloud backends are available in this build. A backend whose client
 * id/App Key was not provided at build time is omitted entirely and never shown
 * in the UI.
 *
 * `expect` because availability depends on the build-time-generated
 * `BuildConfig`, which lives in the platform source set.
 */
expect object CloudStorageAvailability {
    val dropboxAvailable: Boolean
    val googleDriveAvailable: Boolean
    val oneDriveAvailable: Boolean
    val available: List<CloudStorageType>
}

/**
 * The [CloudStorageType.entries] filter both platform actuals share: adding a new backend then
 * has exactly one `when` to update, not one per platform. Declaration order of [CloudStorageType]
 * drives the UI display order (see `SettingsViewModel.availableCloudTypes` / `SetupViewModel`).
 */
internal fun availableCloudStorageTypes(
    dropbox: Boolean,
    googleDrive: Boolean,
    oneDrive: Boolean,
): List<CloudStorageType> = CloudStorageType.entries.filter {
    when (it) {
        CloudStorageType.DROPBOX -> dropbox
        CloudStorageType.GOOGLE_DRIVE -> googleDrive
        CloudStorageType.ONEDRIVE -> oneDrive
    }
}
