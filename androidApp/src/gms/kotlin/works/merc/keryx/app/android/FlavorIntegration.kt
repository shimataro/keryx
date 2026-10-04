package works.merc.keryx.app.android

import works.merc.keryx.app.core.AndroidGoogleDriveBackend
import works.merc.keryx.app.data.cloud.PlayServicesGoogleDriveBackend

/**
 * The Google Drive backend of the flavors that ship Google Play services (`github`, `play`) — the
 * `fdroid` flavor's counterpart of this file answers `null`. See [AndroidGoogleDriveBackend].
 */
internal val googleDriveBackend: AndroidGoogleDriveBackend? = PlayServicesGoogleDriveBackend
