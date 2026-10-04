# F-Droid store listing

This directory holds the **store listing F-Droid reads from this repository** (its
[fastlane-style `metadata/android/<locale>/`](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/)
layout). It is not read by any build or workflow here; F-Droid's own build picks it up from
the tagged commit once the app is listed (see [`distribution/fdroid/`](../distribution/fdroid/README.md)
for the `fdroiddata` recipe draft).

## Layout

```text
metadata/android/
├── en-US/
│   ├── title.txt                 # app name
│   ├── short_description.txt     # ≤ 80 characters
│   ├── full_description.txt      # ≤ 4000 characters, plain text
│   ├── changelogs/<versionCode>.txt   # ≤ 500 characters; the version code of the release
│   └── images/phoneScreenshots/  # screenshots (PNG/JPG) — add before submitting
└── ja-JP/
    └── (same files)
```

The copy derives from [`distribution/play/listing/`](../distribution/play/listing/) (itself derived
from [`README.md`](../README.md) and [`PRIVACY.md`](../PRIVACY.md)) with the differences of the
F-Droid build: **no Google Drive** (it needs Google Play services, which the `fdroid` flavor
omits) and **no update check of its own** (F-Droid updates the app). Update `en-US` and `ja-JP`
together, and keep them in step with the Play listing when the feature list or privacy claims
change.

`changelogs/<versionCode>.txt` is named by the Android `versionCode`, which
`androidApp/build.gradle.kts`'s `versionCodeOf` derives from the version (`0.21.0` → `210099`; see
[`docs/build.md`](../docs/build.md)'s "Android (APK / AAB)"). Add one per release that F-Droid
should show notes for.

`images/phoneScreenshots/` is empty on purpose (`.gitkeep`): screenshots are added by hand.
