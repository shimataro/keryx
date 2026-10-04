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
│   └── images/
│       ├── icon.png              # 512×512, generated (see below)
│       ├── featureGraphic.png    # 1024×500, generated (see below)
│       └── phoneScreenshots/     # 1.png, 2.png, 3.png — shown in file-name order
└── ja-JP/
    └── (same files)
```

The copy derives from [`distribution/play/listing/`](../distribution/play/listing/) (itself derived
from [`README.md`](../README.md) and [`PRIVACY.md`](../PRIVACY.md)) with the differences of the
F-Droid build: **no Google Drive** (it needs Google Play services, which the `fdroid` flavor
omits) and **no update check of its own** (F-Droid updates the app). Update `en-US` and `ja-JP`
together, and keep them in step with the Play listing when the feature list or privacy claims
change.

There are deliberately no per-release notes (fastlane's `changelogs/`): release notes are written
once, in the GitHub Release, and the F-Droid recipe's `Changelog` field links to the GitHub Releases
page (see [`distribution/fdroid/README.md`](../distribution/fdroid/README.md)).

`images/icon.png` and `images/featureGraphic.png` are generated from the SVG masters under
[`design/`](../design/) — the app icon, and the same feature graphic the Play listing uses (its light
variant, one per locale). Regenerate them after changing a master:

```bash
B=fastlane/metadata/android
rsvg-convert -w 512 -h 512 design/icons/svg/app_icon.svg -o $B/en-US/images/icon.png
cp $B/en-US/images/icon.png $B/ja-JP/images/icon.png
rsvg-convert -w 1024 -h 500 design/google-play/feature-graphic/light-en.svg -o $B/en-US/images/featureGraphic.png
rsvg-convert -w 1024 -h 500 design/google-play/feature-graphic/light-ja.svg -o $B/ja-JP/images/featureGraphic.png
```

Use `rsvg-convert` (`brew install librsvg`), not ImageMagick: its built-in SVG delegate silently
drops the arc paths of the app icon (see `design/icons/make_desktop_icons.sh`).

`images/phoneScreenshots/` holds three screenshots per locale, taken on an Android emulator
(1344×2992) running the `fdroid` flavor in that language: the article list, the reader, and the feed
list drawer with folders and tags. To retake them:

- Subscribe only to feeds whose content is safe to show in a store listing — the current set is
  NASA and English Wikipedia (featured articles, picture of the day) for `en-US`, and JAXA press
  releases, NAOJ news and Japanese Wikipedia (featured articles) for `ja-JP`. Avoid commercial news
  sites (their logos and headlines) and check every headline in the shot.
- Freeze the status bar with SystemUI demo mode (`sysui_demo_allowed`, then `clock -e hhmm 1200`,
  `notifications -e visible false`, `network -e mobile hide`), and capture with
  `adb exec-out screencap -p`, which yields a plain rectangle without the emulator's rounded corners.
- Losslessly shrink the result with `optipng -o7 -strip all` before committing.
