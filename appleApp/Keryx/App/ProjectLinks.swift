/// Mirrors `composeApp/src/commonMain/kotlin/works/merc/keryx/app/ui/settings/AboutLicenses.kt`'s
/// `PROJECT_URL`/`LICENSES_URL`. Plain constants, not `strings.xml` resources — a GitHub repo URL
/// has no per-locale variation, unlike `website_url` (which does: `/en/` vs. the Japanese default).
/// Kept as a literal here rather than relocated into `:shared`, since composeApp's own copy is the
/// single source of truth and this is the one place the Apple app needs the same value.
let projectUrl = "https://github.com/shimataro/keryx"
let licensesUrl = "\(projectUrl)/blob/HEAD/THIRD-PARTY-LICENSES.md"
