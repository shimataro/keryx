package works.merc.keryx.app.core

import works.merc.keryx.app.BuildConfig

actual object AppInfo {
    actual val version: String = BuildConfig.VERSION
    actual val updateRepo: String = BuildConfig.UPDATE_REPO
}
