# F-Droid submission drafts

`works.merc.keryx.yml` is a **draft of the metadata file for
[`fdroiddata`](https://gitlab.com/fdroid/fdroiddata)** (`metadata/works.merc.keryx.yml`) — nothing
here is read by any build or workflow in this repository. It is submitted by hand as a merge request
to `fdroiddata`; the store listing (title, descriptions, changelogs, screenshots) is read from
[`fastlane/metadata/android/`](../../fastlane/README.md) in this repository, not from the recipe.

The file is kept exactly in the form `fdroid rewritemeta` writes — field order, line wrapping and no
comments — because the `fdroiddata` merge request's CI fails on any file that `rewritemeta` would
change. The explanations therefore live here rather than in the file; after editing it, run
`fdroid rewritemeta works.merc.keryx` in an `fdroiddata` checkout (or F-Droid's `buildserver`
image) and copy the result back.

The `Builds` entry is a placeholder until the first submission (see the end of this file).

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
- The last `prebuild` line writes `appVersion=$$VERSION$$` (the build's `versionName`) into
  `gradle.properties`, which is where both `androidApp` and `shared` read the version from
  (`findProperty("appVersion")`). It is a `prebuild` line rather than a `gradleprops` entry because
  automatic updates (below) copy the previous build and rewrite only `versionName`, `versionCode`
  and `commit` — `$$VERSION$$` is substituted per build, a literal in `gradleprops` would not be.
- `gradleprops` passes the **public** OAuth client identifiers: F-Droid does not sign up for API
  keys, and Dropbox's App Key and OneDrive's Client ID are PKCE public clients with no secret
  (see [`docs/build.md`](../../docs/build.md)'s "Cloud Storage Integration"). The values in the draft
  are placeholders — fill in **exactly** the values `release.yml` builds with (the
  `DROPBOX_APP_KEY` / `ONEDRIVE_CLIENT_ID` secrets): they are compiled into the APK, so any other
  value breaks the reproducible-build match below. Google Drive needs none: the F-Droid build has
  no Google Drive.
- `Binaries` / `AllowedAPKSigningKeys` — reproducible builds. `release.yml` attaches the `fdroid`
  APK signed with the app signing key as `Keryx-<version>-android-fdroid.apk`; F-Droid builds the
  same tag, compares, and only if the two match apart from the signature publishes the
  developer-signed one. `AllowedAPKSigningKeys` is the SHA-256 of that certificate (from
  `apksigner verify --print-certs` on any release APK). F-Droid users then share the GitHub and Play
  signature and can switch channels without reinstalling. If a release does not match, F-Droid
  publishes nothing for it — see `docs/build.md`'s "Publishing to F-Droid" for what keeps the build
  reproducible.
- `AntiFeatures: NonFreeNet` — the optional sync talks to Dropbox / OneDrive, which are
  proprietary network services.

- `UpdateCheckMode: HTTP` / `AutoUpdateMode: Version v%v` — new releases are picked up
  automatically. `UpdateCheckMode: Tags` cannot do it here: it looks for a literal `versionCode` in
  the Gradle files and fails with "Couldn't find any version information", because this app derives
  `versionName` and `versionCode` from the release tag at build time (`appVersion` → `versionCodeOf`
  in `androidApp/build.gradle.kts`; `0.22.0` → `220099`). Instead `release.yml` attaches
  `fdroid-version.json` (`{"versionName":"…","versionCode":…}`, the code computed by `versionCodeOf`
  itself) to every release, and `UpdateCheckData` reads it from
  `releases/latest/download/fdroid-version.json` — `latest` skips pre-releases, so only stable
  releases are picked up. A new version then becomes a new `Builds` entry with `commit: v<version>`.
  - F-Droid's FAQ asks developers not to compute the version at build time; the recipe still records
    a literal `versionName` / `versionCode` per build, but a reviewer may question it.
  - `checkupdates` runs in the merge request's CI and reads that URL, so the recipe can only be
    submitted once a stable release carrying `fdroid-version.json` exists.

Before submitting, replace `versionName` / `versionCode` / `commit` with the first release tag that
contains the `fdroid` flavor, and fill in the two client identifiers. Tags before v0.22.0 have no
`:shared` module, so this recipe (which edits files in that layout) cannot be used with them.
