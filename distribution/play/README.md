# Google Play store listing drafts

This directory holds **draft copy for the Google Play Console store listing** —
nothing here is read by any build or workflow. Every value has to be copy-pasted
into Play Console by hand (App content → Store listing / Main store listing);
`r0adkll/upload-google-play`, the action `release.yml` and `publish-play.yml` use
to publish the AAB (see [`docs/build.md`](../../docs/build.md)'s "Publishing to
Google Play"), only uploads the binary and release notes, never store-listing
metadata.

## Layout

```
listing/
├── en-US/
│   ├── title.txt               # ≤ 30 characters
│   ├── short-description.txt   # ≤ 80 characters
│   └── full-description.txt    # ≤ 4000 characters
└── ja-JP/
    └── (same three files)
```

Directory names match Play Console's own BCP-47 locale codes. Content is derived
from [`README.md`](../../README.md) / [`README.ja.md`](../../README.ja.md) and
[`PRIVACY.md`](../../PRIVACY.md) / [`PRIVACY.ja.md`](../../PRIVACY.ja.md) — keep
it in sync with those if the app's feature list or privacy claims change, the
same way [`docs-translation.md`](../../.claude/rules/docs-translation.md) keeps
`docs/*.md` and `docs/*.ja.md` in lockstep (this directory isn't covered by that
rule, since it lives outside `docs/`, but the same discipline applies: update
`en-US` and `ja-JP` together).

Play strips Markdown — these files are plain text. A `•` bullet and blank lines
for paragraph breaks are as much formatting as the full description gets.
