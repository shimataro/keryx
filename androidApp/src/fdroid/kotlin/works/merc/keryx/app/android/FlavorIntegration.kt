package works.merc.keryx.app.android

import works.merc.keryx.app.core.AndroidGoogleDriveBackend

/**
 * The `fdroid` flavor ships no Google Play services, so it has no Google Drive backend: Google Drive
 * is never offered, exactly as on a device without Play services. Its counterpart in
 * `src/gms/` (the `github` and `play` flavors) answers with `:androidGms`'s implementation. See
 * [AndroidGoogleDriveBackend].
 */
internal val googleDriveBackend: AndroidGoogleDriveBackend? = null
