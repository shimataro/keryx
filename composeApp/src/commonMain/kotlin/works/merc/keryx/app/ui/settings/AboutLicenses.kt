package works.merc.keryx.app.ui.settings

/** Project homepage (also where releases are published), opened from the About screen/dialog. */
const val PROJECT_URL: String = "https://github.com/shimataro/keryx"

/**
 * Consolidated third-party license notices, opened from the About dialog.
 *
 * Derived from [PROJECT_URL] so a repository rename only needs to change that one
 * constant. The actual list of bundled dependencies lives in `THIRD-PARTY-LICENSES.md`
 * at the repository root (kept in sync with `gradle/libs.versions.toml`).
 */
const val LICENSES_URL: String = "$PROJECT_URL/blob/master/THIRD-PARTY-LICENSES.md"
