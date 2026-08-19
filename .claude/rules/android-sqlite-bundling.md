---
paths:
  - "**/DatabaseDriverFactory.android.kt"  # picks the SupportSQLiteOpenHelper.Factory
  - "**/DatabaseMerger.android.kt"
  - "**/DatabaseSnapshot.android.kt"
  - "**/libs.versions.toml"                # bundled-SQLite dependency declaration
---

# Design policy: Android's bundled SQLite is a temporary crutch — plan the exit now

Android's `DatabaseDriverFactory` uses a **bundled SQLite** `SupportSQLiteOpenHelper.Factory`
instead of the device's own `android.database.sqlite.SQLiteOpenHelper`, because two pieces of
this app's SQL need a newer SQLite than most Android devices ship: `articles_fts`'s
`tokenize='trigram'` needs SQLite ≥3.34, and `DatabaseSnapshot`'s `VACUUM INTO` needs ≥3.27.
Android's own SQLite only guarantees ≥3.34 starting at **API 34 (Android 14)** — below that,
relying on the device's SQLite would silently degrade search or break the snapshot export on
older devices.

**SQLite version alone does not guarantee FTS5.** AOSP's own SQLite build (`external/sqlite`'s
`Android.bp`) enables `FTS3`/`FTS4` but not `FTS5`, and also sets
`SQLITE_OMIT_COMPILEOPTION_DIAGS`, so `PRAGMA compile_options` cannot be used at runtime to
confirm FTS5 availability either. Reaching the API 34 (Android 14) version floor above is
therefore necessary but not sufficient — the exit criteria below add an explicit runtime probe
for this reason.

**This is not meant to be a permanent dependency.** Keep the `SupportSQLiteOpenHelper.Factory`
selection confined to a single seam (DI, or inside `DatabaseDriverFactory.android.kt`) so it can
be swapped without touching `DatabaseMerger`, `DatabaseSnapshot`, `FtsManager`, or any SQL —
none of them care which factory produced the `SqlDriver`.

## Exit criteria

Once Android 14+ (API 34+) device share reaches **~99%** (check the Play Console distribution
dashboard, or Google's official Android version distribution data), drop the bundled library:

1. Raise `minSdk` to 34. (The exit criterion assumes essentially every supported device is
   already there — raising `minSdk` is what makes that assumption safe rather than merely
   convenient.)
2. Before swapping the factory, add an instrumentation test that runs on a real device/emulator
   at the target API level and executes the production `articles_fts` `CREATE VIRTUAL TABLE`
   statement (`tokenize='trigram'`) through `FrameworkSQLiteOpenHelperFactory`. Treat a
   successful `CREATE VIRTUAL TABLE` as the FTS5 runtime gate — do not rely on
   `PRAGMA compile_options`, which the AOSP build's `SQLITE_OMIT_COMPILEOPTION_DIAGS` flag makes
   unreliable. If the probe fails, keep the bundled driver, or revise this exit criteria.
3. Swap the `SupportSQLiteOpenHelper.Factory` implementation from the bundled-SQLite one to
   `androidx.sqlite:sqlite-framework`'s `FrameworkSQLiteOpenHelperFactory`, which wraps the
   device's own `SQLiteOpenHelper` directly.
4. Remove the bundled SQLite dependency from `gradle/libs.versions.toml`, and its row from
   `THIRD-PARTY-LICENSES.md` (`.claude/CLAUDE.md` constraint #8).
5. No SQL changes needed: `VACUUM INTO` already works on the standard driver from API 30
   onward, well below the API 34 floor this migration requires anyway.
6. Confirm the APK shrinks — the bundled library's per-ABI native `.so` files go away.

Treat this as its own follow-up task once the Android port has shipped, not something to fold
into the initial Android work.
