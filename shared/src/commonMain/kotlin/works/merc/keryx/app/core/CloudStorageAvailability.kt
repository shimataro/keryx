package works.merc.keryx.app.core

/**
 * Cloud storage backends the app can support. `id` is persisted in local settings.
 *
 * Declaration order is the UI display order — iterate [entries]/[CloudStorageAvailability.available]
 * rather than relying on any map iteration order for display. The OAuth backends come first, with
 * Google Drive — the one hidden on some builds/devices (see [CloudStorageAvailability]) — after the
 * other two, so hiding it never shifts their position. Nextcloud, the self-hosted provider, is listed
 * last of all.
 */
enum class CloudStorageType(
    val id: String,
    /**
     * Whether connecting asks the user for a server address first — true for a self-hosted provider
     * (Nextcloud). Both UIs read this to put the server-address dialog in front of the connect.
     */
    val requiresServerUrl: Boolean = false,
) {
    DROPBOX("dropbox"),
    ONEDRIVE("onedrive"),
    GOOGLE_DRIVE("google_drive"),
    NEXTCLOUD("nextcloud", requiresServerUrl = true),
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

    /** Always true: Nextcloud needs no build-time key (the user names the server), so every build offers it. */
    val nextcloudAvailable: Boolean
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
    nextcloud: Boolean,
): List<CloudStorageType> = CloudStorageType.entries.filter {
    when (it) {
        CloudStorageType.DROPBOX -> dropbox
        CloudStorageType.ONEDRIVE -> oneDrive
        CloudStorageType.GOOGLE_DRIVE -> googleDrive
        CloudStorageType.NEXTCLOUD -> nextcloud
    }
}
