# F-Droid submission drafts

`works.merc.keryx.yml` is a **draft of the metadata file for
[`fdroiddata`](https://gitlab.com/fdroid/fdroiddata)** (`metadata/works.merc.keryx.yml`) — nothing
here is read by any build or workflow in this repository. It is submitted by hand as a merge request
to `fdroiddata`; the store listing (title, descriptions, changelogs, screenshots) is read from
[`fastlane/metadata/android/`](../../fastlane/README.md) in this repository, not from the recipe.

What the recipe encodes, and why:

- `gradle: [fdroid]` — the `fdroid` flavor, the only one without Google Play services and with the
  in-app update check off (see [`docs/build.md`](../../docs/build.md)'s "Android (APK / AAB)").
- `sudo` installs Debian's `openjdk-25-jdk-headless`: the build needs a JDK 25, and F-Droid's
  build server has to take it from Debian.
- `prebuild` deletes the `foojay` toolchain-resolver line from `settings.gradle.kts` — F-Droid's
  scanner rejects that plugin because it downloads JDKs at build time. With it gone, Gradle uses
  the JDK from `sudo` above.
- `rm: [androidGms]` plus the second `prebuild` `sed` take the Google Play services module out of
  the build altogether. The `fdroid` flavor does not depend on it, but the scanner inspects every
  file in the source tree whatever flavor is built, and `androidGms/build.gradle.kts` names
  `play-services-auth`. The `sed` deletes the `include(":androidGms")` line and the two
  `project(":androidGms")` dependencies (of the `github`/`play` flavors) that would otherwise point at a
  module that no longer exists.
- `gradleprops` passes the **public** OAuth client identifiers: F-Droid does not sign up for API
  keys, and Dropbox's App Key and OneDrive's Client ID are PKCE public clients with no secret
  (see [`docs/build.md`](../../docs/build.md)'s "Cloud Storage Integration"). The values in the draft
  are placeholders. Google Drive needs none: the F-Droid build has no Google Drive.
- `AntiFeatures: NonFreeNet` — the optional sync talks to Dropbox / OneDrive, which are
  proprietary network services.

- `AutoUpdateMode: None` / `UpdateCheckMode: None` — `fdroid checkupdates` cannot follow releases
  automatically. With `UpdateCheckMode: Tags` it fails with "Couldn't find any version information",
  because it looks for a literal `versionCode` in the Gradle files, while this app derives both
  `versionName` and `versionCode` from the release tag at build time (`appVersion` →
  `versionCodeOf` in `androidApp/build.gradle.kts`). So each release that F-Droid should ship is a
  new `Builds` entry (`versionName`, `versionCode` computed by `versionCodeOf` — `0.21.0` → `210099`
  — and the tag as `commit`) added to the recipe by merge request. Automating this would mean
  keeping a literal version in a file the release process updates; not done.

Before submitting, replace `versionName` / `versionCode` / `commit` with the first release tag that
contains the `fdroid` flavor, and fill in the two client identifiers.
