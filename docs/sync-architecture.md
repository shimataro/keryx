# Keryx Sync Architecture

[日本語](sync-architecture.ja.md)

Target: cloud sync (Dropbox / OneDrive / Google Drive / Nextcloud). Implementation is in `domain/SyncRepository.kt`
(scheduling, gates, user-facing state), `domain/DeltaSync.kt` (the flow below), `domain/SyncPointer.kt`,
`domain/TransportCoverage.kt` (what a base covers, the base cache, the recorded send), `domain/LegacyCloudFile.kt` (the
pre-delta migration and the legacy watch), `domain/MergeSql.kt`,
`domain/ReduceSql.kt`, `domain/TransportSchema.kt`, `domain/MergeFailureClassifier.kt`, `domain/MergeSchema.kt`,
`domain/SnapshotSql.kt`, `platform/DatabaseMerger`, `platform/DeltaExporter`, `platform/TransportFiles`.

## Design Philosophy

- **Delta sync.** Each device appends only the changes it made itself to the cloud, as small immutable delta files;
  every other device merges them. Once enough deltas have accumulated, any device folds them into a full snapshot
  (a *base*). Nothing is ever re-uploaded just because it exists: a sync where nothing changed transfers no payload.
- **State-based, not operation-based.** A delta carries the *current state* of each changed row, with its
  per-field last-write-wins stamps — never "star article X" as an operation. The merge is per-field-group
  last-write-wins, so it is **idempotent** (applying a file twice changes nothing), **commutative** (the order files
  arrive in does not matter) and **associative** (merging files one by one or after folding them together gives the
  same result). Duplicate files, files arriving out of order, and re-applying a file after a crash are therefore all
  harmless — the rest of this document relies on that.
- **Only local changes are sent.** A change that arrived through a merge is never re-sent, so changes do not echo
  back and forth between devices.
- **No change is ever unreachable.** Every change is, at every moment, in at least one delta file or base in the
  cloud. Files are deleted only under the garbage-collection rules that keep this true (see "Bases and Garbage
  Collection").
- **The transport files are SQLite** (gzip-compressed), in a dedicated *transport schema* — the synced tables and
  columns only, with no UNIQUE or foreign-key constraints, plus a few metadata tables. The merge attaches them with
  `ATTACH DATABASE`, as before.
- **The database is never held in memory.** `CloudStorage` moves files by local path — `download(path, destPath)`
  streams the response body straight to disk, `upload`/`create` stream the file straight onto the wire — so peak
  memory is a fixed handful of 64 KB buffers. The shared streaming helpers live in `data/cloud/CloudFileTransfer.kt`
  (on `kotlinx-io`, in `commonMain`).

## Cloud File Structure

Names are flat: Google Drive, OneDrive and Nextcloud address files by basename only, so the kind of each file is
encoded in its name rather than in a folder.

```bash
/keryx-sync.json                         ← pointer: {"format":3,"generation":"<genId>"}; updated with a revision check
/ks-<gen>-b-<dev>-<n>-<hash12>.db.gz     ← base: every synced row folded together (immutable)
/ks-<gen>-d-<dev>-<seq>-<hash12>.db.gz   ← delta: device <dev>'s <seq>-th batch of changes (immutable)
/keryx.db.gz                             ← after migration: a stub whose user_version is 1000 (see "Migration")
/keryx-YYYYMMDD-HHMMSS.db.gz.bak         ← archive of the pre-delta file (never pruned)
```

- `<gen>` is the current **generation**, named by the pointer. A cloud-data reset starts a new generation; files of
  older generations are left in place as an archive and never read again.
- `<dev>` is the writing device's sync identity (`sync_state.ks_device_id`), issued afresh every time the device
  joins a generation.
- `<hash12>` is the first 12 hex digits of the SHA-256 of the *uncompressed* file. Two files with the same name
  therefore always have the same content, which is what lets every write be a plain `create` whose atomicity does
  not matter (Google Drive's is not atomic): a second `create` of the same name is the same file, and two different
  files never share a name. Every download is checked against it.
- Names are matched against a strict pattern before use; anything that does not match is someone else's file and is
  ignored (a user may keep files of their own in Nextcloud's `/Keryx` folder). Only names that passed the pattern
  are ever embedded in SQL.

Conflicts can arise only on the two files that are ever replaced — the pointer and the `keryx.db.gz` stub — and
both are written with the provider's revision check (Dropbox: `rev`, a real server-side compare-and-set; Google
Drive: `<id>:<version>`, compared client-side, so not atomic — see "Google Drive: an upload can overwrite another
device's upload unnoticed" in [known-issues.md](known-issues.md); OneDrive: `eTag` via `If-Match`; Nextcloud:
`ETag` via `If-Match`, 412/423 → conflict). No lock file is used. On Nextcloud these files live in the `/Keryx/`
folder of the user's own files rather than an app folder.

### Listing (`CloudStorage.list`)

`list(prefix)` returns every file whose name starts with `prefix` (name, revision, size), following every page:
Dropbox `list_folder` / `list_folder/continue`, Google Drive `files.list` in `appDataFolder` with `nextPageToken`,
OneDrive the app folder's `/children` with `@odata.nextLink`, Nextcloud a Depth-1 `PROPFIND` on `/Keryx`. Drive's and
OneDrive's listings are eventually consistent — a file just written may be missing for a while — which the receiving
rules tolerate (a gap stops the cursor; it never skips anything). Drive may also list two files of the same name;
for sync files that only ever means two copies of the same content.

### Transport schema (`domain/TransportSchema.kt`)

A delta or base holds the six synced tables (`feeds`, `articles`, `tags`, `feed_tags`, `folders`,
`global_settings`) with their synced columns only — `feeds`' fetch state (now `feed_fetch_state`, device-local) and
`articles.search_text` (recomputed by the receiver) are not in it — and without UNIQUE or foreign-key constraints:
a base folds rows from several devices, and two devices can legitimately hold different ids for the same tag name.
Each file carries `PRAGMA user_version` and:

- `ks_meta(key, value)` — `format`, `generation`, `device` (the writing device), `created_at`, `kind` (`delta`/`base`)
  and `number` (the delta's `seq` or the base's `n`); generation, device, kind and number must match the file name;
- `ks_vector(device, seq)` (bases only) — per device, the highest `seq` such that every delta `1..seq` is covered;
- `ks_covered(name, created_at)` (bases only) — the delta files this base folded that its vector does **not** imply,
  each with the delta's own creation time (its `ks_meta.created_at`; NULL when unknown).

A base covers a delta `(device, seq)` either by its vector (`seq ≤ ks_vector[device]`) or by name (`ks_covered`). The
vector implies a delta only while `ks_covered` names no delta of the same `(device, seq)`: when a cloned database
wrote two different files with one `(device, seq)`, the base lists every copy it folded, so a copy it never saw is not
implied. `ks_covered` therefore stays small — the deltas of the last `KS_DELTA_GRACE_MS`, deltas past a gap, and clone
copies — rather than growing with every delta ever folded (see "Bases and Garbage Collection" for when the vector may
extend).

`TransportSchema` holds the DDL, the expected tables/columns/types per version, and the transport migrations.
`KeryxDatabase.Schema.migrate` is never run on a transport file (it would add the device-local tables and triggers).

## Local Bookkeeping (schema version 3)

| Object | Purpose |
| --- | --- |
| `sync_outbox(seq INTEGER PRIMARY KEY AUTOINCREMENT, tbl, pk, pk2, heavy)` | Every local change to a synced row, in order |
| `sync_capture(id = 1, enabled, full_pending)` | Whether changes are recorded (only while connected), and whether a full send is due |
| `sync_merging(flag)` | Present only inside a merge transaction (inserted first, deleted just before `COMMIT`), so no other connection ever sees it |
| `sync_applied(name)` | Cloud files already merged (including this device's own) |
| `sync_cursor(device, seq)` | Per device, the highest `seq` up to which every delta has been merged |
| `ks_search_pending(rowid)` | Articles whose `search_text` must be recomputed (see "FTS5 Handling") |
| `feed_fetch_state(feed_id, etag, last_modified, error_count, last_error)` | A feed's fetch state on this device — never synced |

**Triggers** on the six synced tables append to `sync_outbox` (`AFTER INSERT` / `AFTER UPDATE`), guarded by
`WHEN (SELECT enabled FROM sync_capture) = 1 AND NOT EXISTS (SELECT 1 FROM sync_merging)` — so a merge never
records what it writes. An `UPDATE` is recorded only when a synced column actually changed (`OLD.x IS NOT NEW.x OR
…`), so a refresh that rewrites an unchanged row records nothing. For articles:

- a change to `content`, `summary`, `title`, `url`, `author`, `published_at`, `thumbnail_url` or the body's stamp
  `body_updated_at` (or an insert) is recorded with `heavy = 1` — the delta carries the article's body;
- a change to the read, star or deletion columns alone is recorded with `heavy = 0` — the delta carries a *light*
  row, with `content` and `summary` left NULL;
- `cached_at` is recorded only when its day (`cached_at / 86400000`) changes — a refresh bumps it on every article
  still in the feed, and sending that every 30 minutes would dwarf every other change (see "Sync Target Article
  Range" for what this means for cache cleanup);
- a change to `updated_at` or `search_text` alone is not recorded.

No row of a synced table is ever physically deleted (every delete is a tombstone), so there are no `DELETE`
triggers.

`sync_state` keys: `last_synced_at`, `ks_device_id`, `ks_generation`, `ks_last_seq`, `ks_attempt` (the file being
sent: its number, name, outbox high-water mark, and whether it is the full send), `ks_pointer_rev` (the pointer
revision whose generation is `ks_generation`), `ks_received_at` (when this device last completed a receive, by its own
clock), `ks_bases` (what this device knows of its generation's bases — each one's vector, `ks_covered` and creation
time — so it can tell what a base covers without downloading it), `ks_legacy_stub_rev`. `sync_state` and the tables
above are device-local and never leave the device.

## Sync Flow (`SyncRepository.sync()`)

Everything below runs under `SyncRepository`'s `mutex`.

0. **Resume an interrupted send** (once step 1 has named the generation). If `ks_attempt` is set, the file it names
   was written locally (and perhaps uploaded) but never committed. Its saved file is decompressed and checked against
   the hash in its name; if it matches, it is uploaded again under the same name and committed (step 4 of "Sending").
   If the file is missing, its hash does not match, or the record itself cannot be read, the device takes a **new
   sync identity** (new `ks_device_id`, `ks_last_seq = 0`): the outbox entries the attempt covered were never deleted,
   so they are sent again under the new identity, and the old identity's file — whether or not it reached the cloud —
   can only ever be applied harmlessly. A disk-corrupted file is never uploaded. If the generation has changed since,
   the attempt is simply dropped (the rejoin's full send covers it). With no attempt recorded, a saved file left by a
   crash between a commit and its deletion (only ever the last committed one) is deleted.
1. **Read the pointer.** `metadata(keryx-sync.json)`; the file is downloaded only when its revision differs from
   `ks_pointer_rev`.
   - Neither the pointer nor a full `keryx.db.gz` exists (no file, or the stub) → this is the first device ("Joining") — unless this device has
     already sent into its own generation (`ks_last_seq ≥ 1`: a first device interrupted before creating the
     pointer), which then creates the pointer for that generation.
   - No pointer, but `keryx.db.gz` holds a full database → migrate from the pre-delta format ("Migration").
   - Its generation differs from `ks_generation` → rejoin ("Joining") — another device reset the cloud data.
   - It cannot be parsed or lacks a field → `CloudDataIncompatibleException` (a reset rewrites it).
   - Its `format` is newer than this build knows → `SchemaVersionException` ("update the app").
   - **Legacy watch:** `metadata(keryx.db.gz)` too; a revision other than `ks_legacy_stub_rev` means an older build
     wrote a full database there, which is absorbed ("Migration").
2. **List** `ks-<gen>-*` (`CloudStorage.list`, every page). A file carrying this device's own `<dev>` that it did not
   write — a `seq ≤ ks_last_seq` whose name is not in `sync_applied`, or a `seq > ks_last_seq` that is not the
   `ks_attempt` name — means the database was cloned (restored from a backup, or copied to another machine) and both
   copies are writing as the same device: this device rejoins under a new identity. A delta of its own that a known
   base covers is not counted (it may have been garbage-collected and still be listed for a while). After receiving,
   a known base covering a delta of this device's identity above `ks_last_seq` means the same — a copy restored from a
   backup older than the grace period sees the original's newer deltas only through the bases — and the device rejoins
   before sending. Nothing is lost meanwhile — the hash in the name keeps the two copies' files apart, and receivers
   apply both.
3. **Receive** (see "Receiving").
4. **Send** (see "Sending").
5. **Fold and collect garbage**, when the thresholds are met (see "Bases and Garbage Collection").
6. Record `last_synced_at`.

A sync with nothing to do costs three requests (two `metadata` calls and one listing page) and no payload. The
`keryx.db.gz` watch goes away with the rest of the
pre-delta compatibility at v1.0.0. `SYNC_MAX_RETRY` now bounds
only the retries of a revision-checked write (the pointer, the stub); delta and base names never collide.

### Receiving

1. **What to fetch.** Every listed delta whose name is not in `sync_applied`. Then, per device D, look for a `seq`
   above `sync_cursor[D]` that is neither applied nor listed — a delta that was already garbage-collected. If a base
   covers it, that base is fetched too, and the listed deltas it covers count as applied. What a base covers is read
   from `ks_bases`; a listed base this device has never looked at is downloaded to find out. If no base covers it, the
   gap is taken as the provider's listing not having caught up yet (Drive's and OneDrive's listings are eventually
   consistent): the cursor stops below it, every other file is still merged (the merge is commutative), and the next
   sync looks again. A known base that covers something this device lacks is fetched as well.
   - **Joining, or away too long.** When joining — and whenever `ks_received_at` is older than `KS_DELTA_GRACE_MS`
     minus `KS_RECEIVE_STALE_MARGIN_MS` (one day, for listing delay and clock differences) — every listed base this
     device does not know is fetched. A delta is garbage-collected only once it is that old (see below), so a device
     that received within that time has seen every delta that can have gone; one that has not may have missed deltas
     that left no gap behind (the last deltas of an identity that went quiet), which only the bases still hold.
     `ks_received_at` is recorded only by a receive that left no gap, missed no file, and saw at least one base it
     knows — so a joining device whose listing does not show the generation's base yet keeps looking for it.
   - **Gone meanwhile.** A listed file whose download fails while `metadata` finds it absent (garbage-collected, the
     listing not caught up) counts as not listed: nothing is merged from it and the cursor stops below it.
2. **Validate** every file ("Validating Cloud Files"). If any file fails, nothing is merged.
3. **Fold** the files into one temporary transport database with `ReduceSql` (per primary key, per field group,
   last-write-wins — the same rules as the merge, without its local guards), so the merge runs once however many
   files arrived.
4. **Merge** with `DatabaseMerger.mergeTransport` in a single transaction (see "Merge"): `INSERT INTO sync_merging` first,
   then `MergeSql.all`, then the `sync_applied`/`sync_cursor` updates for every file merged and the
   `ks_search_pending` registrations, then `DELETE FROM sync_merging`. Merging and recording what was merged
   therefore commit together or not at all — a crash in between re-merges the same files next time, which the
   merge's idempotence makes harmless.
5. After the commit: recompute `search_text` for `ks_search_pending` ("FTS5 Handling"), `ftsManager.indexMissing()`,
   then `driver.notifyListeners(...)` for every synced table — the merge wrote through its own connection, so
   SQLDelight's flows must be re-triggered for the UI to reflect it.
6. Names no longer listed are dropped from `sync_applied`; if one ever reappears, applying it again is harmless.
   This device's own deltas stay until a known base covers them, so a listing that does not show a just-sent delta yet
   never makes it look like a clone's (step 2).

### Sending

1. **Build the delta** with `platform/DeltaExporter` (`commonMain`; the SQL is `domain/DeltaExportSql`, run over
   `TransportConnections`' per-platform connection), which opens its own connection on a new, empty transport file
   and `ATTACH`es the source database to it:
   - **Ordinary delta** — inside one read transaction: `S = MAX(sync_outbox.seq)`, then the **current** row for
     every key with an outbox entry `≤ S`, plus every row those rows reference (a feed's folder, an article's feed,
     a `feed_tags` row's tag and feed), so the receiving merge never finds a referenced row missing. An article
     with no `heavy` entry is written as a light row. Because `S` and the rows come from one snapshot, any write
     after it carries a `seq > S` and goes into the next delta.
   - **Full send** (when joining) — from a `VACUUM INTO` copy, as the pre-delta upload did, so the live database's
     read lock is held only for the copy; `S` is read from the copy too. Rows identical to the base just merged
     (`EXCEPT`) are left out — a fresh device with an empty database sends almost nothing.
2. **Record before uploading**: the file is named by the hash of the exporter's own output (never opened for writing
   again — on Android that would add `android_metadata` and change its bytes), written compressed to the app's data
   directory as `pending-<n>.db.gz`, and `ks_attempt = (n = ks_last_seq + 1, name, S, full)` is recorded. A file with no
   rows at all — a full send whose every row the cloud already holds — is not uploaded: the outbox entries it covered
   are deleted and `full_pending` cleared at once.
3. **Upload** with `create(name)`. An "already exists" answer means the same file is there (same name, same
   content), which counts as success.
4. **Commit**, in one transaction: `DELETE FROM sync_outbox WHERE seq ≤ S`, `ks_last_seq = n`, add the name to
   `sync_applied`, clear `full_pending` if this was the full send, clear `ks_attempt`. Then delete the saved file.

Wherever this is interrupted, nothing is lost or doubled: unsent changes are still in the outbox, and a file that
did reach the cloud is re-sent under the same name before the commit (step 0 of the flow).

### Bases and Garbage Collection

**When.** After sending, once the listed deltas no known base covers reach `KS_COMPACT_MIN_DELTAS` (100) files or
`KS_COMPACT_MAX_DELTA_RATIO` (25%) of the largest listed base's size. Bases this device does not know are downloaded
first — they may cover those deltas already — and the thresholds checked again. Any device may fold; two folding at
once is safe. Folding is skipped on a metered network or under Data Saver / Low Data Mode, even for a manual sync
(`NetworkUsagePolicy.compactionAllowed`), and may be cancelled at any point. Nothing waits for a fold: a transient
failure is logged and left to a later sync, while a corrupt or too-new input is reported like any other cloud file.

**How.** Every base that no other base dominates, plus every listed delta none of them covers, is validated and
folded with `ReduceSql` into a new base. A base is **never built from the local database**: the local merge skips
rows its guards reject (a name collision, say), and a base built from it would silently drop them for every other
device. The new base is named `ks-<gen>-b-<dev>-<n>-<hash12>`, `n` one above this device's highest listed base.
`ks_vector` combines the input bases' vectors and extends them over the folded deltas that continue them without a gap
— **counting only deltas created at least `KS_DELTA_GRACE_MS` ago** (`ks_covered.created_at`); `ks_covered` then keeps
only the folded deltas the extended vector does not imply, plus every copy of a `(device, seq)` it folded more than
one of (`TransportFiles.finalizeBase`). The age rule is what makes the vector safe to trust: a clone's duplicate of an
old `(device, seq)` would have had to stay invisible to every listing for the whole grace period to be implied without
having been folded. The new base is uploaded with `create`; if any input fails validation, no base is built.

**Garbage collection** keeps "no change is ever unreachable" true:

- A delta may be deleted only once a base covering it exists (confirmed with `metadata` just before) and the delta
  itself was created at least `KS_DELTA_GRACE_MS` (7 days) ago — its `ks_covered.created_at`, or for a delta the
  vector implies, true by construction (`BaseCoverage.coveredBefore`) — so a device that syncs within a week catches
  up from deltas alone, without downloading a base. (The delta's own age, not the base's: bases are rebuilt more often
  than weekly on an active account, and a rule on the base's age would never let a delta go.)
- A base may be deleted only while another base strictly dominates it (confirmed with `metadata` just before): a
  vector at least as high in every component, every delta it names explicitly covered by the other too — a clone copy
  it lists must be listed by the other, not merely implied — and more coverage or, for equal coverage, a greater name.
  So two devices can never delete each other's bases.

**Cost of a new device.** One base (about the size of the pre-delta database file) plus at most the deltas below the
thresholds; deltas a base covers are not downloaded. That is at most about 1.25× what joining cost before.

### Joining, Reset, Disconnect

- **Joining** (connecting, switching provider, a generation change, a detected clone): issue a new `ks_device_id`
  and clear the cursors, `sync_applied`, the outbox and `ks_attempt` — in the same transaction, set
  `sync_capture = (enabled 1, full_pending 1)`, so every write from then on is recorded. Then receive everything and
  make the full send. The result is what the pre-delta "merge, then upload the whole database" produced.
- **The first device** joins a new generation, uploads its full send as the first base, and only then creates the
  pointer (`create`) — so whenever a pointer is visible, its base exists. That base, `b-<dev>-1`, covers the device's
  own delta `1` through its vector (`ks_vector = {dev: 1}`) without a delta file of that number ever existing, so every
  base is still the fold of the deltas it covers. A device that loses a concurrent pointer creation sees a different
  generation on its next sync and rejoins; the base it sent is never pointed at.
- **Reset** (`resetCloudData()`, the recovery for unusable cloud data): join a new generation, upload the local
  full state as its base (as the first device does), then replace the pointer with a revision check. A revision mismatch (another device reset
  first) aborts and re-reads the pointer. The old generation's files are left in place as an archive. Every other
  device sees the new generation and rejoins, sending its own full state — so data only another device held comes
  back, as it did when other devices merged the re-created file before. Once the pointer is replaced, a pre-delta
  `keryx.db.gz` that is not the stub is archived and replaced by it (see "Resetting (Archiving) Cloud Data").
- **Disconnect / switch** (`clearSyncFailureState()`, under the same mutex): `sync_capture.enabled = 0` (nothing is
  recorded while local-only), and the `ks_*` keys, the outbox, `sync_applied`, the cursors and any saved pending
  file are cleared. `ks_search_pending` stays — it belongs to the local search index.

### Validating Cloud Files

Every downloaded delta, base or pre-delta database passes these checks, in order, before any of it is merged —
including the inputs of a fold. One failure, and nothing from that sync is merged. Checks run read-only on the
temporary file.

| # | Check | On failure |
| --- | --- | --- |
| 1 | Name matches the pattern | Not ours — ignored |
| 2 | Download completes | Transient; retried next sync |
| 3 | gzip decompresses (capped at `MAX_SYNC_DB_SIZE_BYTES`), the SQLite header, the hash in the name (a pre-delta database has no hash in its name) | Any of these failing → downloaded once more in the same sync; failing again → corrupt |
| 4 | `user_version` | Newer than this build → `SchemaVersionException` (at `keryx.db.gz`, an empty file of `user_version` 1000 is the stub, recognized before this check; any other newer version, 1000 with tables included, is this failure) |
| 5 | Expected tables and columns for that version (`TransportSchema`; `MergeSchema` for a pre-delta database) | Missing → corrupt. No expectation registered for the version → undetermined, treated as transient (never answered with a reset) |
| 6 | An older version is migrated up (`TransportSchema` migrations; `KeryxDatabase.Schema.migrate` for a pre-delta database) | An SQLite error while migrating → corrupt |
| 7 | Types and value ranges, by a query generated from the expected schema: ids are non-empty text, stamps are integer or NULL, booleans are 0 or 1, NOT NULL columns hold no NULL | Any violating row → corrupt |
| 8 | `ks_meta` (generation, kind, device, number) matches the file name; a base's `ks_vector`/`ks_covered` types | Corrupt |
| 9 | References are closed: every feed's folder, every article's feed, every `feed_tags` row's tag and feed is in the same file | Corrupt |

"Corrupt" is `CloudDataIncompatibleException`, handled as before: automatic syncing is suspended, and the
notification center offers a reset (a new generation). A reset recovers even the corrupt file's content as long as
the device that wrote it still exists, since every device re-sends its full state. A corrupt file is never skipped:
skipping it would lose its changes without a word.

Check 7 exists because SQLite does not enforce column types. A text value in a stamp column compares greater than
every number, so it would win every last-write-wins comparison from then on — and once in the local database, the
generated SQLDelight code's `getLong` would throw when reading it, crashing the app. It applies to pre-delta
databases too, which the pre-delta merge never checked.

Validation and merge failures are classified inside `DatabaseMerger`/`SyncRepository` and returned as `Result.Err`
(`CloudDataIncompatibleException`, `SchemaVersionException`, or a transient `CloudStorageException`); only
`CancellationException` propagates. What validation cannot catch is a well-formed file whose content is logically
wrong — a bug in the writing device; the hash only proves the file is what was uploaded.

### Debouncing

How often a local change triggers a sync decides how many delta files are written, so `SyncScheduler` keeps two
windows, both measured from the last change (a burst collapses into one sync):

- **Structural changes** (subscribe/unsubscribe, rename, folders, tags, global settings, a single-feed refresh) call `scheduleSync()` and sync after `SYNC_DEBOUNCE_MS` (5 seconds).
- **Reading-state changes** (read/unread, star, mark-all-read) arrive one by one while reading, so they call `scheduleDeferredSync()` and wait for `DEFERRED_SYNC_DEBOUNCE_MS` (5 minutes) of quiet instead. A structural change arriving meanwhile shortens the wait to the 5-second window, and carries the reading-state changes with it.
- `SyncRepository.flushPendingSync()` syncs at once if anything is still pending, and is called when the app leaves the foreground: Android's `MainActivity.onStop` (not on a configuration change), the iOS scene entering the background (inside a background task), and the desktop window being hidden to the tray — Compose desktop and the native macOS app alike. Quitting the desktop app does not wait on the network for it; the next launch's startup sync sends what is pending. It first waits out a sync already in flight (which cleared the pending flag when it started), so the caller's background grace period covers that upload too. Before that, it waits — for at most `LOCAL_WRITES_FLUSH_WAIT_MS` (10 seconds) — for local writes still queued off the caller's thread (a read/unread or star change, a global-setting change, a feed-list action such as unsubscribing or moving a feed), counted in `PendingLocalWrites`: such a write schedules its sync only once it runs, so a change made just before leaving would otherwise find nothing pending yet.
- On Android and iOS the flush goes through `flushOnLeavingForeground()`, which skips an exit the app caused itself: `BrowserOpener` marks its launch (`ExternalAppLaunch`) and the next exit consumes the mark, so reading an article in the browser does not write one delta per article. The mark is cleared whenever the app is back in front. The changes stay pending for the long window, the next sync, or the next real exit.
- Any sync that runs first — a refresh cycle's, a manual one — picks the pending changes up too: an "unsynced changes" flag is cleared as a sync starts (its delta includes them) and restored on every exit but success — an error result, an exception or a cancellation (iOS cancels the refresh cycle's sync on backgrounding) — and a debounce window that expires with the flag already cleared does nothing.
- The refresh itself (`FeedRepository.refreshFeeds`, only ever run inside a `RefreshCycleRunner` cycle) schedules no sync. A refresh-then-sync cycle syncs right after it; the startup sync-then-refresh cycle has nothing after its refresh, so it schedules one short-window sync (`scheduleSync()`) for what the refresh wrote.
- A read/unread or star call that changes nothing (re-opening an already-read article) writes nothing and schedules nothing: the single-row updates are guarded on the state changing, so `read_at` / `starred_at` are stamped only by a real change. Last-write-wins therefore never lets merely re-opening an article beat another device's later "mark unread" — the unread state stays.

Deferring a change never loses it, even when the sync that would have sent it fails or is cancelled: the next sync of any kind finds it in the outbox (see "Sending" above). Only how soon other devices see it changes.

### Sync Progress Feedback

`SyncRepository` exposes `syncPhase: StateFlow<SyncPhase>` (`IDLE`, `CHECKING`, `DOWNLOADING`, `MERGING`,
`INDEXING`, `PREPARING`, `UPLOADING`, `ARCHIVING`), so the cloud-sync settings tab can show *what* a sync is
currently doing rather than only *whether* one is running (the `syncing` flag of the `ActivityCenter.activity`
snapshot, a plain boolean). Every transition happens inside the same `mutex` `sync()`/`resetCloudData()`
already hold, so a sync queued behind another never observes a stale phase left by the one ahead of it, and the
`finally` inside that lock always resets to `IDLE` on the way out — success, a classified `Result.Err`, or an uncaught exception alike.

The phases map onto the steps above: `CHECKING` for the pointer/stub `metadata()` calls and the listing (steps 1–2),
`DOWNLOADING` for fetching cloud files (receiving, and a fold's inputs), `MERGING` for validation, folding and
`DatabaseMerger.merge()`, `INDEXING` for the `search_text` recomputation and `ftsManager.indexMissing()`,
`PREPARING` for building a delta or base (`DeltaExporter`, `ReduceSql`, compression), and `UPLOADING` for the
`create` calls and the pointer/stub replacement. `resetCloudData()` goes through `PREPARING`/`UPLOADING` for its new
generation's base; `ARCHIVING` is used while the pre-delta file is archived and the stub put in its place.

An `AUTOMATIC` sync skipped by `autoSyncSuspended` never enters `trackSync` at all (see "Automatic-Sync
Suspension" below), so `syncPhase` stays `IDLE` for its entire (instant) duration — consistent with that skip
not spinning the sync spinner either. There is no byte-level progress (no phase reports a fraction downloaded
or uploaded) — `CloudStorage`'s `download`/`upload`/`create` take no progress callback, so a phase is the
finest granularity available without changing that interface across all three providers.

### Automatic-Sync Suspension

`SyncRepository.sync(trigger: SyncTrigger = MANUAL)` takes who is asking. `SyncTrigger.AUTOMATIC` — the
debounced-write consumer, `runStartupMaintenance` (shared by desktop's `StartupTasks.kt`, Android's startup
path, and the Apple SDK's `KeryxSdk`), `backgroundUpdateLoop` (`domain/BackgroundRefreshLoop.kt`, run by
desktop's `main.kt` and the Apple SDK's `KeryxSdk`), and Android's `FeedRefreshWorker` and
`DeferredSyncWorker` — is subject to
a gate: while `autoSyncSuspended` (a `StateFlow<Boolean>`)
is true, an `AUTOMATIC` call skips the download/merge/upload cycle entirely and returns `Result.Ok(Unit)` without
spinning the sync spinner or touching the notification center, so a known-unusable cloud DB is not re-downloaded
and re-merged on every debounced write. `SyncTrigger.MANUAL` (the default, used by every UI-triggered sync — the
toolbar/menu "sync now", "Refresh All", the initial connect-time sync in both `SettingsViewModel.connect()` and
`SetupViewModel`) **always runs for real**, so a person who explicitly asked for a sync always gets a real attempt
and the failure that explains why, never a silent no-op.

The gate is set by `updateAutoSyncGate` (called from both `sync()` and `resetCloudData()`, right before `emitErrorNotification`): a result carrying `CloudDataIncompatibleException` sets it, any `Result.Ok` clears it. `SchemaVersionException` is deliberately excluded — it is equally permanent, but its fix is "update the app", and gating background syncing on it would hide the moment a newly-installed version starts working again. `scheduleSync()` also checks the gate before enqueueing a debounce signal, so a write burst does not even spin up the debounce wait while the cloud is known-unusable.

`autoSyncSuspended` is deliberately **in-memory, not persisted**: a process restart is a free, honest retry (another device may have fixed the cloud data in the meantime), and the gate's entire purpose — not re-downloading/re-merging the same unusable file, and not re-raising the same notification, within one running process — needs nothing more durable than that. It clears on any successful sync (manual or automatic), on a successful `resetCloudData()`, and via `clearSyncFailureState()` — which also clears the mirrored failure-reason text — called when the connection that produced it is disconnected or switched (`SettingsViewModel.disconnect()`/`switchTo()`).

Nothing about the reset/notification UI is affected by the gate: the notification-center `ResetCloudData` button and the settings screen's reset button are unconditional (not gated), and `lastSyncError` is left untouched by a skipped `AUTOMATIC` call, so the cloud-sync tab keeps showing why sync is currently broken.

### Network Gate (Metered Networks and Data Saver)

A second `AUTOMATIC`-only gate sits beside the one above. Deltas are small, but a sync can still move large files —
a join's full send, a base to catch up from, a fold — so automatic syncing on a phone still waits for an
unmetered network by default, as it did when every sync uploaded the whole database. The OS
reports two signals through `platform/NetworkConditionMonitor` (an interface obtained from the
`platformNetworkConditionMonitor()` expect, as a `core/NetworkCondition(metered, dataSaver)` flow):
whether the current network is **metered** (Android: the default network lacks
`NET_CAPABILITY_NOT_METERED` — cellular, tethering, or a Wi-Fi the user marked as metered; iOS:
`nw_path_is_expensive` — cellular or a Personal Hotspot), and whether the user turned on the OS's **data-saving mode** (Android:
Data Saver, i.e. `restrictBackgroundStatus == ENABLED`, which Android applies to metered networks
only; iOS: Low Data Mode, `nw_path_is_constrained`, which can be set on any network including Wi-Fi).
No network at all counts as unrestricted. Desktop and macOS report neither (`supported = false`) and
are never restricted.

`domain/NetworkUsagePolicy` turns that into the one decision every caller shares:

| | Unrestricted | Metered | Data Saver / Low Data Mode |
| --- | --- | --- | --- |
| Automatic sync (`SyncTrigger.AUTOMATIC`) | runs | deferred, unless `syncOnMeteredNetwork` is on | deferred |
| Automatic feed refresh (an `AUTOMATIC` `RefreshCycleRunner` cycle) | runs | runs | skipped |
| Manual sync / Refresh All / pull-to-refresh (`MANUAL`) | runs | runs | runs |

`syncOnMeteredNetwork` is a device-local setting (`local_settings.json`, off by default).
`SyncRepository` takes the gate as an `automaticSyncAllowedFlow: () -> Flow<Boolean>` lambda — lazy for the
same reason as `cloudProvider`, since `SettingsRepository` depends on `SyncRepository` as its
`SyncScheduler` — which `SharedModule` builds with `NetworkUsagePolicy.automaticSyncAllowedFlow` from the monitor and the setting.

When `sync(AUTOMATIC)` finds the network disallowed (and a provider is connected), it does not enter
`trackSync` at all — no spinner, no phase, no notification, `lastSyncError` untouched — and returns
`Result.Ok(Unit)`, but unlike the suspension gate it remembers that something is pending:
`syncDeferredForNetwork` (a `StateFlow<Boolean>`) goes up, and a single waiter on the app scope
(started once, however many syncs are deferred meanwhile) calls `scheduleSync()` the moment the gate
flow turns true, and drops the flag at that moment: the deferral is over, and the sync it schedules either
runs (a failure then reaches `lastSyncError` as usual, so the flag never claims a wait nothing is
performing) or is deferred anew if the network turned restrictive again before the debounce elapsed. A
successful sync (a manual one included) also drops the flag and cancels the waiter — unless another sync
was deferred while it ran (a generation counter tells), since that deferral covers changes it did not
upload. Automatic syncing becoming suspended (`autoSyncSuspended`) and `clearSyncFailureState()`
(disconnect / switch) drop it too; a deferral racing a disconnect never raises the flag.

Like `autoSyncSuspended`, the deferral is **in-memory**: the pending changes themselves are already
persisted in `sync_outbox`, so the next sync that is allowed sends every change made in the meantime. The waiter only covers the time the process stays alive; Android additionally
hands the deferred sync to a `WorkManager` job that waits for a network the sync is allowed on (an unmetered one, unless the user opted in to syncing on a metered one), so it also happens
after the app is closed (see "Deferred sync" in [background-update.md](background-update.md)). On iOS a
deferral left by a closed app is picked up by the next background refresh or foreground sync that the
network allows.

The automatic feed refresh stays allowed on a metered network because each fetch is a conditional
request and usually small; only the OS's data-saving mode, the user's explicit request to save data,
stops it.

## Merge (`DatabaseMerger` + `MergeSql`)

The folded transport file (or a validated pre-delta database) is attached as `cloud` and merged table by table.
A transport file goes through `DatabaseMerger.mergeTransport`: `KeryxDatabase.Schema.migrate` is never run on it, its
`user_version` is checked against `TransportSchema.VERSION` (an older one is migrated by `TransportFiles.prepareForMerge`),
and the ambiguous `SQLITE_ERROR` case of the classification below is checked against the transport schema
(`TransportFiles.schemaMatches`) instead of `MergeSchema`. `merge` keeps the pre-delta mode for the migration.

> [!IMPORTANT]
> SQLDelight's JVM `JdbcSqliteDriver` opens a new connection per statement for file DBs. Therefore, executing `ATTACH` through the driver makes it invisible to subsequent merge statements (`no such table: cloud.*`). The merge is done on a **single dedicated connection** in `platform/DatabaseMerger`: attach → version check → merge (transaction) → detach.

Merge SQL (`MergeSql`) key points:

- **Explicitly specify columns**, and only columns present both in the transport schema and in a pre-delta database
  migrated to the current schema, so one statement set serves deltas, bases and the migration.
- **A tie is decided by value, in every statement.** When both sides carry the same stamp, the group whose
  normalized values compare greater wins. Precisely (`SyncLww`), each version of a field group gets the rank row
  value `(stamp IS NOT NULL, COALESCE(stamp, 0), c1 IS NOT NULL, COALESCE(c1, 0), c2 IS NOT NULL, COALESCE(c2, 0),
  …)`, where `c1, c2, …` are every column of the group — its stamp column(s), then its values — and the two ranks are
  compared with SQLite's row-value `>` (BINARY collation). A version replaces the other only when its rank is
  strictly greater. So a stamped version beats an unstamped one (NULL means "no such event"), the greater stamp wins,
  and at an equal stamp the values decide in column order: NULL lowest, then SQLite's own ordering (integers
  numerically and below text, text byte by byte). Every element is non-NULL, so the comparison is never NULL, and
  identical versions never replace each other. A group with two stamps (an article's retention group, below) ranks
  by `any stamp IS NOT NULL, MAX(COALESCE(s1, 0), COALESCE(s2, 0))` in place of the first two elements; an article's
  body group puts two elements in front of all of these (below). The pre-delta rule ("a tie goes to the cloud") converged only because the
  cloud held a single file; with deltas, devices merge files in different orders, and a side-based rule would let
  that order decide. A device's own write always stamps above what its row already holds (see "Design Philosophy"
  in [db-schema.md](db-schema.md)), so a tie only arises between writes made independently on two devices in the
  same millisecond.
- **Duplicates inside `cloud` are resolved deterministically.** A folded file can hold two rows with the same unique
  key (`feeds.url`, `articles(feed_id, guid)`, a tag or folder name) from different devices. Statements that match
  by such a key consider only the winner among them — greatest `updated_at`, then greatest id. Statements that
  resolve a reference by id (`mergeFeedFolderId`, `feedTags`) still see every row.
- **Rows absent from `cloud` mean nothing.** A delta holds only some rows; the statements whose outcome depends on a
  referenced row (`mergeFeedFolderId`'s "the folder is not alive in `cloud` → NULL", `feedTags`' tag resolution)
  get the same answer as before because every delta carries the rows its rows reference (checked by validation
  step 9).
- feeds / tags / folders / global_settings: last-write-wins (including logical deletion). The `ON CONFLICT` in the
  feeds statement handles only content fields (url/title/description/… + `updated_at`); the user-edited fields are
  merged by dedicated statements using their own stamps: `mergeFeedFolderId` (`folder_id` / `folder_updated_at`),
  `mergeFeedSortOrder`, `mergeFeedCustomTitle`, `mergeFeedDeletedAt`. `folder_id` is resolved in four branches:
  NULL if the cloud's referenced folder isn't alive in `cloud.folders`; else the cloud's `folder_id` if that folder
  is alive in `main`; else a same-name lookup joining `main.folders`/`cloud.folders` (both alive); else NULL. A
  feed's fetch state (`feed_fetch_state`) is device-local and never merged.
  feeds are matched by **`id`**, which is deterministically generated from `url` as **UUIDv5**
  (`IdGenerator.feedId`), so the same feed has the same id on every device.
- **tags / folders: per-field last-write-wins**, like feeds: name (`name_updated_at`), color (`color_updated_at`,
  tags), sort order (`sort_order_updated_at`) each by its own stamp; `updated_at` stamps creation, deletion and
  reactivation. Two devices changing different fields of the same tag both keep their change. Rows are matched by
  id, else by name (two devices that created the same name independently, with different ids, converge on one).
- articles, three groups:
  - **Body** — `content`, `summary`, `title`, `url`, `author`, `published_at`, `thumbnail_url` (and `feed_id` /
    `guid`, which the local merge never rewrites on an existing row), stamped by `body_updated_at`. A version with
    `content` outranks one without, and one with any body (`content` or `summary`) outranks one with none, before the
    stamp is compared; then `body_updated_at`, then the values. So `content` is effectively OR-merged, a summary
    never replaces content, a light row (NULL `content`/`summary`) never erases a body, and the title, URL, author
    and the rest always come from the same version as the body. `summary` is dropped to NULL once the article has
    `content`. `body_updated_at` moves only when a body column changes (a refresh that rewrites an unchanged article
    leaves it), never on a read, star or deletion — so a device that merely opened an article cannot make its older
    body beat another device's newer one.
  - **Read** — `is_read` by `read_at`.
  - **Retention** — `is_starred`, `starred_at`, `deleted_at` and `deleted_updated_at` as one group, ranked by
    `MAX(COALESCE(starred_at, 0), COALESCE(deleted_updated_at, 0))`: **the newer operation wins**, and the whole group
    comes from the version that holds it. A star clears the deletion locally (`updateStarStatus`), and both a star
    and a cleanup stamp above both stamps of their row, so the newest event always wins its group. (The earlier rule
    — separate star and deletion groups, plus "a star newer than the deletion revives the article" — was not
    commutative: a deletion, a later star and a still later unstar, merged in different orders, gave different
    results.) As a consequence, an article revived by a star on another device and then unstarred stays visible
    until the next cache cleanup deletes it again.

  `cached_at` and `updated_at` take the newer value (`updated_at` is the row's generic stamp, no group's key), and
  `created_at` the earlier. `search_text` is not merged: an article whose body the merge
  filled in or changed is registered in `ks_search_pending`, and a newly inserted article starts with `''` (see
  "FTS5 Handling"). Articles are matched by **`id`** (UUIDv5 of `(feed_id, guid)`, `IdGenerator.articleId`).
- feed_tags: last-write-wins. Imported only if the feed exists in main. The tag is used as-is if it is alive in
  main, else looked up by name through `cloud.tags`; a row whose tag resolves to neither is skipped.
- `NOT EXISTS` / `EXISTS` guards skip rows colliding with main (same URL, different ID, etc.) so UNIQUE / FK
  violations do not fail the whole transaction.
- `MergeSql.all` order: folders (by name, then by id, then the per-field statements), feeds, the four `mergeFeed*`,
  tags (by name, by id, per field), articles, feed_tags, global_settings. Folders precede feeds (for
  `feeds.folder_id`), and the `mergeFeed*` statements follow both.
- The bookkeeping tables (`sync_outbox`, `ks_search_pending`, `sync_merging`, …) carry no constraints, so the
  classification below still holds that main can only already violate FOREIGN KEY constraints.

`ReduceSql` folds transport files into one with the same per-field rules and tie-break, keyed by primary key only —
no local guards and no name resolution, which belong to each device's own merge.

### Merge Failure Classification

`DatabaseMerger.merge` classifies a failure from `mergeUnclassified` (its private, unclassified implementation) using SQLite's **error code** — not message text, which is locale- and driver-version-fragile — found by walking the cause chain for an `org.sqlite.SQLiteException` (`findSqliteCause`, bounded against a cause cycle). `SchemaVersionException` is caught and rethrown first, before the classifying catch-all, so it is never reclassified. Classification is deliberately conservative: an error it doesn't recognize (or one with no `SQLiteException` in its cause chain) is rethrown unchanged, so a miss falls through to `SyncRepository`'s own catch-all as a transient `CloudStorageException` rather than regressing behavior.

The split between the two layers is: the desktop `actual` only reduces `resultCode.code and 0xFF` to a driver-independent `SqliteFailureCategory` (`CORRUPT` / `CONSTRAINT` / `STATEMENT_ERROR` / `OTHER`), gathers the evidence the table below needs, and logs the verdict; the policy in the table below is `domain/MergeFailureClassifier.classify` in `commonMain` — a pure function of (category, error-code name, the local foreign-key verdict, a lazily-invoked `validateSchema` callback, a lazily-invoked cloud-integrity callback) returning a `CloudDataIncompatibleException` or `null` (= rethrow the original failure unchanged). The `validateSchema` callback is invoked only for the ambiguous `STATEMENT_ERROR` case and the cloud-integrity callback only for `CORRUPT`, since each reopens the downloaded file. Nothing about the classification itself is platform-specific, so a target whose driver exposes no numeric error code (Android's `android.database.sqlite.SQLiteException`) only has to produce the same category.

The Apple `actual` (`DatabaseMerger.apple.kt`) applies the same mapping and the same `MergeFailureClassifier`, but finds the code differently, walking the cause chain (bounded the same way) for either of two exception types: its own `SqliteException` from `platform/RawSqliteConnection.kt` (the dedicated merge connection), which carries the result code directly, or SQLiter's `SQLiteExceptionErrorCode`, which `NativeSqliteDriver` throws while the cloud file is being opened and migrated. SQLiter keeps the raw `errorCode` private, so `errorType.code` — the primary code only — is the one readable value; that getter throws for a code SQLiter has no enum entry for, which is treated as "no code found" (rethrown unchanged).

Before attaching, the Apple `migrateCloudIfOlder` brings an older cloud file up to the local schema by opening it through `NativeSqliteDriver` in rollback-journal mode. That driver opens (and so migrates) the file lazily, on its first statement — the same reason `DatabaseDriverFactory.apple.kt` runs one on creation — so it runs a trivial `SELECT 1` before closing the driver, then re-reads the file's `user_version` and throws an `IllegalStateException` if it still isn't the local schema version. That exception deliberately carries no SQLite result code, so the merge leaves it unclassified (transient): a migration that silently did nothing may be an app-side fault, and must never be answered with the destructive cloud-data reset.

| SQLite primary result code | Classification |
| --- | --- |
| `SQLITE_NOTADB`, `SQLITE_CORRUPT`, `SQLITE_FORMAT`, `SQLITE_EMPTY` | **Permanent only once the cloud file is shown to be broken.** After the rollback, the downloaded cloud file — a temp file nothing else writes — is opened without creating it and checked with `PRAGMA quick_check`: problems reported, or opening/checking it fails with `SQLITE_NOTADB`/`SQLITE_CORRUPT` → `CloudDataIncompatibleException`; `ok` → the corruption is in the *local* DB, so rethrown unchanged; anything else (undetermined) → rethrown unchanged. A corrupt local DB is therefore never answered with a cloud-data reset. |
| `SQLITE_CONSTRAINT` (covers every extended `SQLITE_CONSTRAINT_*` variant — `_UNIQUE`, `_NOTNULL`, `_FOREIGNKEY`, etc. — since an extended code's low byte is always its primary code) | **Permanent only once *main* is ruled out** → `CloudDataIncompatibleException`. `MergeSql`'s `NOT EXISTS`/`EXISTS` guards already rule out every collision with *main*'s own rows (see above), and UNIQUE / NOT NULL are always enforced on main, so what remains is the cloud DB's own row set violating a constraint — a duplicate `url` inside the cloud DB, a NULL where the cloud's own (laxer) schema allowed one, etc. — or a **foreign-key violation already present in main** (data from before foreign keys were enforced, or a migration), which the merge's `PRAGMA foreign_keys=ON` would expose if a statement modified that row. Today's `MergeSql` only ever writes a foreign-key column with a value guarded to exist in main, or NULL (`mergeFeedFolderId`), and never deletes a parent, so this guards statements added later. A failure the driver reports, by its extended result code, as a UNIQUE, PRIMARY KEY or NOT NULL violation cannot come from main's existing state — those are enforced on every write — so it is classified at once, without the check or the re-run below, which could only reach the same verdict while holding the merge's write lock longer. Desktop and Apple read that code; Android's requery exposes none, so Android always takes the full path. (main can only already violate FOREIGN KEY constraints; adding CHECK constraints or triggers to the schema must revisit this.) Everything below runs on the merge's connection **inside the failed merge's still-open transaction**, so no other writer can change main in between (a re-subscribe supplying an orphaned article's missing parent, say). (1) `PRAGMA main.foreign_key_check` gives V0, what main already held — earlier statements ran under immediate FK checking, so they added nothing. An empty V0 rules main out → `CloudDataIncompatibleException`. (2) Otherwise it sets `PRAGMA defer_foreign_keys=ON` and re-runs the failed statement and every one after it (`MergeFailureClassifier.remainingStatements`) in the same transaction, so only a non-FK constraint fails immediately. Continuing from the failed statement reaches the same state as starting over — the statements before it already passed immediate FK checking, and a constraint failure undoes the failed statement's own changes — and it is the only option on Android, where requery turns any statement starting with `ROLLBACK` into a rollback of its whole transaction, so a `ROLLBACK TO` savepoint cannot be issued (the reasoning is in `MergeFailureClassifier.mainRuledOut`'s KDoc). A constraint failure in the re-run → a non-FK constraint from the cloud rows → `CloudDataIncompatibleException`. A clean re-run whose `foreign_key_check` (V1) holds a row V0 did not → the cloud rows brought in a new FK violation → `CloudDataIncompatibleException`. A clean re-run with nothing new in V1 → the failure came from a pre-existing local violation → rethrown unchanged. Anything else — another failure, a transaction SQLite had already ended, a check that fails — → rethrown unchanged. Either way the whole transaction is then rolled back; nothing is committed. A local orphan therefore never hides a constraint violation that comes from the cloud. A constraint failure from `migrateCloudIfOlder`, which touches only the cloud file, has main ruled out by construction and is classified. |
| `SQLITE_ERROR` (`no such table`, `no such column`) | **Ambiguous** — this is what a foreign/legacy cloud schema looks like, but also what a broken *local* schema (an unrelated app bug) looks like. Resolved by calling `validateSchema` against the downloaded cloud file: `false` → `CloudDataIncompatibleException`; `true` or `null` (undetermined) → rethrown unchanged, since neither confidently pins the failure on the cloud. |
| Anything else (`SQLITE_CANTOPEN`, `SQLITE_IOERR`, `SQLITE_FULL`, `SQLITE_BUSY`, `SQLITE_LOCKED`, `SQLITE_READONLY`, no `SQLiteException` found, …) | **Transient / app-side** — rethrown unchanged. |

Because classification lives entirely inside `DatabaseMerger.merge` (which only wraps `mergeUnclassified`, not anything the caller does afterward), it structurally cannot see `DeltaSync.mergeFolded`'s post-commit steps — `ftsManager.indexMissing()` and `driver.notifyListeners(...)`, both of which run *after* the merge has already committed. A failure there (e.g. a dropped local `articles_fts` table, itself `SQLITE_ERROR`) reaches `SyncRepository`'s own catch-all unclassified and is reported as `CloudStorageException`, never `CloudDataIncompatibleException` — the merge already succeeded, so offering a destructive cloud-data reset for it would be wrong.

How each `actual` knows the merge transaction is still open differs, since the obvious flags only record what was asked for, not a rollback SQLite performed by itself: desktop registers a rollback listener (`sqlite3_rollback_hook`, via sqlite-jdbc's `SQLiteCommitListener`) on the merge transaction, Apple asks `sqlite3_get_autocommit`, and Android opens a `SAVEPOINT` with the transaction and treats a successful `RELEASE` as proof that it is still inside it — once before the re-run (followed by a fresh `SAVEPOINT`, so nothing re-runs if the transaction has ended) and once after. A re-run starts only once the transaction is proven open, so no statement can ever run outside it and commit into main. The verdict is carried to the classification step for that exact failure only (`MainRuledOutVerdict`), and a failure of the check itself is attached as suppressed, never replacing the original.

`PRAGMA quick_check` on the downloaded cloud DB runs only on the failure path, to tell a corrupt cloud file from a corrupt local DB (see the table above). It is deliberately *not* run on every sync: it is O(DB size), and SQLite already surfaces a corrupt page as a distinct error code the moment the merge touches it, so a whole-file scan on every sync would buy nothing for the pages the merge doesn't visit. Nor could it catch a cloud DB with duplicate/NULL data that violates *this app's* constraints but not the cloud DB's own, since `quick_check` only verifies a DB's internal consistency against its own schema.

## Schema Version

Two versions are in play:

- **The local schema** (`PRAGMA user_version` of `keryx.db`, currently 3). Version 3 (`2.sqm`) adds the sync
  bookkeeping tables and triggers, moves `feeds`' fetch state into `feed_fetch_state`, adds the tag/folder per-field
  stamps and `articles.body_updated_at` (all initialized from `updated_at`), and registers every article whose `search_text` still equals its raw
  `content`/`summary` (rows written before HTML stripping) in `ks_search_pending`.
- **The transport schema** (`user_version` of each delta/base). A file older than this build is migrated up with
  `TransportSchema`'s migrations before use; a newer one raises `SchemaVersionException`, which stops the sync
  without advancing any cursor, so nothing is skipped once the app is updated.

Adding a `.sqm` must update, together, the transport schema (DDL, expectations, migration), `MergeSchema`, and the
column lists the capture triggers compare; a test fails when they disagree. Unknown columns are never ignored: an
older device re-sending a row without them would overwrite the newer columns with NULL.

`DatabaseMerger.validateSchema` / the transport validation return a **nullable** verdict: `null` when no expectation
is registered for a version or the inspection itself fails. `null` is never treated as "incompatible" — it must not
offer a destructive reset for what is really a forgotten registration — and is reported as a transient failure.
`SyncMergerTest.validateSchemaReturnsTrueForValidKeryxDb` and its transport counterpart pin the current versions.

## FTS5 Handling

**Never DROP `articles_fts` on the live DB.** The index never leaves the device: transport files are built from the
synced tables only, and the full send's `VACUUM INTO` copy is only read from.

Index maintenance is two-tier:

- **Hot path (after feed refresh / sync merge)**: `FtsManager.indexMissing()` incrementally indexes only unindexed new articles (O(new rows), does not wipe index). Full rebuild (`'rebuild'`) is O(total indexed text) and heavy, and could reject running searches, so it is not used on hot paths. Indexes of existing articles with updated body text remain stale until the next rebuild (acceptable; they still match old tokens so searches do not regress to zero results).
- **`search_text` after a merge.** `search_text` is not synced. The merge registers every article whose body it
  filled in or changed in `ks_search_pending` (in the merge transaction), and a newly inserted article starts with
  `search_text = ''`. After the commit and **before** `indexMissing()`, `SyncRepository` recomputes `search_text`
  (`domain/SearchTextBackfill`) with `HtmlText.toPlainText` in batches: the HTML is converted outside any
  transaction, then each batch updates the rows and removes them from `ks_search_pending` in one transaction — only
  for rows whose `content` / `summary` are still the ones converted, so a row a concurrent refresh changed stays
  pending and is converted again from its new body. A row whose conversion throws gets `''` (and a log entry) rather
  than blocking the rest. The pending table is committed state, so an interrupted pass resumes at the next sync or
  launch: startup maintenance runs the same backfill first, in the background, then `indexMissing()` (the
  launch-time `ensureIndexed()` has skipped those rows meanwhile). `indexMissing()`
  skips rows still in `ks_search_pending`, so a new article is indexed with its computed text, never with `''`. An
  article that was already indexed keeps its old tokens until the daily rebuild — the same accepted staleness as a
  body changed by a refresh. The daily rebuild recomputes the pending rows first (`maybeRebuildFtsIndex`), since
  `'rebuild'` indexes every row from its current `search_text` and would otherwise index a pending row with `''`.
- **Healing full rebuild (`rebuildIndex()` = `'rebuild'`)**:
  Executed only in the daily idle pass (`domain/StartupMaintenanceTasks.kt`'s `maybeRebuildFtsIndex`, shared by
  desktop and Android, gated by `local_settings.lastFtsRebuiltAt` 24h gate + `ActivityCenter` idle — no sync, no feed
  refresh, and no refresh-then-sync cycle (`refreshCycleRunning`, which also covers the gap between a cycle's refresh
  and its sync) in flight). Rebuilds stale
  existing rows (body text updated since incremental indexing). `'rebuild'` is a single atomic statement (readers
  see only before or after) + `busy_timeout` wait, so running searches do not regress to zero results either.

The two index writers are **mutually exclusive**: `FtsManager` serializes `indexMissing()` and
`rebuildIndex()` behind an internal mutex (both are therefore `suspend`). The daily pass's idle gate is a
lock-free `ActivityCenter` check, so a refresh starting just after it passes would otherwise overlap the
rebuild — always wasted work (a rebuild subsumes an incremental insert), and on a corpus whose rebuild
outlasts `busy_timeout`, a raw `SQLiteException` no caller catches. Searches are deliberately **not**
serialized: they still rely on `'rebuild'` being a single atomic statement plus the `busy_timeout` wait.

On desktop startup, `FtsManager.ensureIndexed()` (initial creation + unindexed row incremental insert) is called
from a `runBlocking` in `main.kt` — the window must not open on an absent index. The writer mutex is
coroutine-based and, that early, can only be held briefly by an `.opml` import dispatched moments before, so
blocking the main thread on it cannot deadlock. Android instead calls the cheaper `ensureIndexedIfTableAbsent()`
on every process start (see "On startup" in [db-schema.md](db-schema.md)'s `articles_fts` section for why). The
Apple SDK creates the table in `KeryxSdk.start` (best effort) and backfills it from `prepareSearchIndex()`.

## Cloud Authentication (OAuth PKCE + Offline Access)

OAuth 2.0 authorization-code-with-PKCE orchestration (PKCE generation, authorization URL building, opening that URL, state verification, code exchange) is consolidated in `OAuthConnectFlow` (`commonMain`, shared by every platform including the Apple app). Provider differences are only in **redirect reception method (`OAuthRedirectTransport`) and endpoints/scopes (`CloudAuthManager` implementation)**, so `DropboxAuthManager` / `GoogleDriveAuthManager` / `OneDriveAuthManager` implement `CloudAuthManager`. All request offline access (Dropbox: `token_access_type=offline`, Google: `access_type=offline` + `prompt=consent`, OneDrive: `offline_access` scope) to **obtain and save refresh tokens**. Opening the authorize URL itself goes through an injected `domain/AuthorizationLauncher` (default: `DefaultAuthorizationLauncher`, which opens the system browser) — see "OAuth authorization on Apple" below for how the Apple app supplies its own.

**The one exception is Google Drive on Android, which does not go through this flow at all** — no `OAuthConnectFlow`, no `OAuthRedirectTransport`, and no refresh token. Play services' `AuthorizationClient` runs the whole consent interaction and hands the app a short-lived access token directly, re-issuing one on demand instead. Everything in this section up to "Token Storage" therefore describes desktop's three providers plus Android's Dropbox and OneDrive; see "Google Drive on Android" below for the remaining case.

Redirect reception method is chosen per provider (see the `.claude/rules/cloud-oauth-transport.md` design rule — prefer the custom URI scheme when both work):

- **Dropbox / OneDrive — Custom URI scheme** (`CustomUriRedirectTransport`):
  - **Mechanism.** Redirect URI is `keryx://oauth2/callback`, shared by both providers and
    disambiguated by `state`. On desktop/Android the authorization URL is opened in the default
    browser, and the OS delivers the URL to the running instance (desktop's `LaunchArgDispatcher`
    parses it via `parseOAuthUri` and feeds a shared `MutableSharedFlow<OAuthCallbackParams>`); the Apple app
    instead uses an `ASWebAuthenticationSession` — see "OAuth authorization on Apple" below.
  - **OneDrive specifics.** Uses the Microsoft Identity platform (`consumers` tenant) and Microsoft
    Graph; it is a **PKCE public client with no client secret** (unlike Google), and stores the sync
    DB in the hidden app folder (`/me/drive/special/approot`, scope `Files.ReadWrite.AppFolder`).
  - **Why OneDrive is personal-accounts-only.** The tenant segment must stay `consumers` rather than
    `common`: the app registration is a "Personal Microsoft accounts only" one (signInAudience =
    Consumer), which Microsoft rejects on `/common` ("the application must not be configured with
    'Consumer' as the user audience") — and only *after* the user submits their address, so it
    surfaces as a generic authentication failure. Widening the registration to work/school accounts
    is not an option either, because `Files.ReadWrite.AppFolder` is a personal-account-only Graph
    permission: an organizational account would force a far broader scope such as
    `Files.ReadWrite(.All)` over the user's entire drive, against the privacy stance in
    `external-spec.md`.
  - **Revocation and concurrency.** Microsoft has no standard token-revocation endpoint, so
    `OneDriveAuthManager.revoke` is a no-op and disconnect just clears the stored tokens. Optimistic
    concurrency uses the DriveItem `eTag` as the `rev`, sent back via `If-Match` (412 → conflict);
    `create` uses `@microsoft.graph.conflictBehavior=fail` (409 → conflict).
- **Google Drive (desktop only) — Loopback** (`LoopbackRedirectTransport`): Android does not use this flow at all — see "Google Drive on Android" below. Google's "Desktop app" client does not allow arbitrary custom schemes; only `http://127.0.0.1:<port>` loopback is accepted. A temporary HTTP server (`com.sun.net.httpserver`; `jdk.httpserver` module is bundled) is started to receive the redirect and stopped after reception. No OS scheme registration is required (`keryx://` registration remains for Dropbox / OneDrive). **Unlike Dropbox, the client secret (`GOOGLE_DRIVE_CLIENT_SECRET`) is also sent for both token exchange and refresh** — despite using PKCE, Google's "Desktop app" OAuth client is not treated as a full public client like iOS/Android, and Google's token endpoint rejects token exchange / refresh without `client_secret` with `invalid_request: client_secret is missing` (regardless of PKCE). Scope is `drive.appdata` only (app-specific hidden folder in the user's Drive). During development, set the OAuth consent screen to "Testing" and register test users.

How the scheme is registered with the OS differs per platform. The Compose macOS build (internal verification only) declares it in Info.plist (`CFBundleURLTypes`) at packaging time; the shipped SwiftUI app registers no scheme (see "Google Drive on Apple" below). Windows and Linux register it at startup, from `registerFileAssociations()`: the Windows path writes `HKEY_CURRENT_USER\Software\Classes\keryx` (the per-user hive, so no admin elevation is needed), the Linux path writes a user-level `.desktop` entry (`$XDG_DATA_HOME/applications/keryx-url-handler.desktop`, default `~/.local/share/applications/keryx-url-handler.desktop`) and a `$XDG_CONFIG_HOME/mimeapps.list` (default `~/.config/mimeapps.list`) association via `LinuxUriSchemeRegistrar`. The Linux entry's `Exec` line must end in `%u` — without it the desktop-entry spec does not hand the URI to the process, and the browser cannot resolve the scheme at all (an "unknown protocol" error). On both platforms the OS then launches the app with the URL as a command-line argument, which `main.kt` forwards to the running instance via single-instance.

**Android** registers the same `keryx://oauth2/callback` scheme declaratively:

- **Registration.** Via an `intent-filter` (`VIEW`/`DEFAULT`/`BROWSABLE`, `scheme="keryx"`
  `host="oauth2"`) on `MainActivity` in `AndroidManifest.xml` — no runtime registration step,
  unlike Windows/Linux.
- **Dispatch.** `MainActivity.onCreate`/`onNewIntent` forward the redirect's data URI to
  `dispatchOAuthCallbackIfPresent`, which classifies it via the same `classifyLaunchArg`
  (commonMain) / `parseOAuthUri` (commonMain) code desktop's `main.kt` uses, then emits into the
  same-shaped `MutableSharedFlow<OAuthCallbackParams>` (a separate instance registered in Android's
  own `platformModule`).
- **Configuration changes.** `launchMode="singleTask"` means an already-running instance receives
  the redirect through `onNewIntent` rather than a fresh `onCreate`; the Activity clears the
  intent's data after a successful dispatch so a later configuration-change recreation (which
  replays the same `Intent` into `onCreate`) doesn't resubmit it.
- **Why this is still secure without exclusive scheme ownership.** Unlike desktop, a custom URI
  scheme's `intent-filter` is not something Android lets an app claim exclusively — another app
  could in principle declare the same scheme — so PKCE (already required for every provider here)
  is what actually protects the code exchange: a redirect intercepted by a different app is useless
  without the matching `code_verifier`.

> [!NOTE]
> **Custom-URI providers on every desktop OS**: `./gradlew :composeApp:run` cannot complete Dropbox / OneDrive linking. On macOS (the Compose build, internal verification only), LaunchServices routes `keryx://` to the packaged `Keryx.app`, so the `gradlew run` instance never receives the redirect. On Windows and Linux, the startup registration deliberately no-ops unless the process is a packaged launcher (`packagedLauncherPath()`), because registering the JDK's own `java` binary as the `keryx://` handler would outlive the Gradle run. To test/perform linking, build the app with `./gradlew :composeApp:createDistributable` and launch it (see [setup.md](setup.md) for details). Google Drive uses loopback reception, so this restriction does not apply and `gradlew run` can complete linking. Android has no such restriction either way — the manifest-declared `intent-filter` works the same whether the APK was built via `installGithubDebug` or a release pipeline.

### Google Drive on Android (Play services `AuthorizationClient`)

Android reaches Google Drive through a different mechanism than every other provider-platform pair
here: Play services' `AuthorizationClient`, not a browser redirect. `:androidGms`'s
`data/cloud/PlayServicesGoogleDriveAuth.kt` holds the whole of it, and that module exists in the
`github` and `play` flavors only — the `fdroid` flavor contains no Play services and never offers
Google Drive.

- **Why the desktop configuration cannot be reused.** Google's OAuth policy deprecates *both*
  redirect styles for its Android client type — the custom URI scheme (cited reason: app
  impersonation risk) and, separately, the loopback redirect. That leaves no redirect-based flow to
  run, so desktop's "Desktop app" client (loopback + `client_secret`) has no Android equivalent.
  This is a Google-specific policy decision, not a general Android restriction: Dropbox's and
  OneDrive's custom-URI redirects work identically on Android.
- **What `AuthorizationClient` gives instead.** `authorize()` with the `drive.appdata` scope returns
  a short-lived (one hour) access token directly on the device. The first call shows the consent
  screen via a `PendingIntent`; every later call answers **without user interaction** for as long as
  the grant stands. Play services owns the grant, so there is **no refresh token, no `client_secret`
  and no backend server** — a backend is only required for the *offline* variant
  (`AuthorizationResult.getServerAuthCode()`), which is deliberately not used here.
- **No client id in the build.** The Android OAuth client is matched by package name + signing
  certificate SHA-1, registered in the Cloud Console (see [build.md](build.md)); nothing is read
  from `BuildConfig`. A build signed with an unregistered key therefore fails at `authorize()` time
  rather than at build time. With no client id to check, `PlayServicesGoogleDriveBackend` reports
  `CloudSession.Provider.isConfigured` as `true` — whether Google Drive is offered at all is the
  device gate below.
- **Gated on the device, not on the distribution channel.** `CloudStorageAvailability.googleDriveAvailable`
  asks the registered `AndroidGoogleDriveBackend` (`:androidGms`'s implementation answers
  `GoogleApiAvailability.isGooglePlayServicesAvailable(...) == SUCCESS`), evaluated once per
  process, and `platformModule` registers the provider only when that holds. With no backend
  registered at all — the `fdroid` flavor — the answer is simply "unavailable". A de-Googled ROM
  (GrapheneOS, LineageOS without GApps) simply never sees Google Drive offered; Dropbox, OneDrive
  and local-only are unaffected. Deliberately **not** keyed on the `github`/`play` product flavor —
  a play-flavored APK can be sideloaded and a github-flavored one runs fine where Play services
  exists, the same reasoning `AndroidUpdateInstaller` applies to `REQUEST_INSTALL_PACKAGES`.
- **Token lifecycle inside `CloudSession`.** Because there is no refresh token, the ordinary
  stored-token path (`CloudSession.CredentialLifecycle.OAuth`) would fall through to its "expired with
  no refresh token" branch and hand out a token that is already dead. The provider's
  `CloudSession.CredentialLifecycle.PlatformOwned` lifecycle replaces the token supply for exactly
  this case: each cloud request asks Play services again
  (with `allowUserInteraction = false`, since a request — least of all the `WorkManager` background
  sync — must never open a consent screen on its own). The connect's token is still written to
  `KeystoreTokenStorage`, but only so `connectedType()` reports the account as connected after a
  restart; its `expiresAtMillis` is `null` because Play services does not report an expiry and
  guessing one would be worse than admitting it is unknown.
- **Re-consent.** When the user withdraws the grant in their Google account, sync starts failing
  with `CloudAuthException`, which the existing sync error path records in the notification center
  with a `ShowSettingsTab("cloud_sync")` action — the same treatment an expired Dropbox refresh
  token gets. Recovering from it needs more than pressing connect again, because a withdrawn grant
  leaves the stored tokens untouched: `connectedType()` still reports the account as connected, so
  the row has no connect button in the first place. What repairs it is a disconnect — that is what
  clears Play services' cached token (see **Disconnect** below) — followed by a connect.
  `CloudSyncTab` offers exactly that as one action: while `SyncRepository.lastSyncAuthFailed` holds,
  the connected row's first action becomes **reconnect** (`SettingsViewModel.reconnect`, a teardown
  plus a fresh connect to the same provider) in place of *reset sync data*. The two swap rather than
  sitting side by side — a third button would change the row's child count with state and overrun
  the width desktop's labelled buttons share with the provider name — and they are mutually
  exclusive anyway: a reset needs a working authorization of its own, so it is precisely the wrong
  recovery to offer while authorization is what broke. *Disconnect* stays beside it either way, so
  leaving is never gated on repairing first.

  `PlayServicesConnectFlow` clears the cached token before requesting one for the same reason,
  covering the cases where the app is *not* connected but Play services' cache has outlived the
  grant anyway — a `clearToken` that failed during an earlier disconnect, a reinstall, or app data
  cleared while the Play services cache survived. Without it, connecting in that state silently
  succeeds with a dead token. It costs one extra Play services round trip on an explicit user
  action and changes nothing when the grant is healthy: a fresh token is minted and no consent
  screen appears.
- **Disconnect.** `PlayServicesGoogleDriveAuthManager.revoke` ignores the stored access token (an
  hour old by then, so Google's revoke endpoint would reject it), fetches a fresh one without user
  interaction, and POSTs it to `GOOGLE_REVOKE_ENDPOINT` — the same endpoint desktop uses. If even
  that is unavailable it reports success rather than opening a consent screen in the middle of a
  disconnect; `CloudSession.disconnect` clears the local tokens either way.
  **It then calls `AuthorizationClient.clearToken` on that same token, and this step is required
  rather than tidy-up.** The revoke above travels over plain HTTPS, outside Play services, so Play
  services never learns the authorization it caches has died; it keeps answering `authorize()` from
  that cache, successfully and with `hasResolution()` clear. Reconnecting then completes with no
  consent screen and every Drive request afterwards fails with 401, with no in-app way back.
  `AuthorizationClient.revokeAccess`
  looks like it should replace the HTTPS revoke outright, but it requires an `Account` this flow
  does not otherwise track and fails inside Play services without one
  (`NullPointerException: ... Account.name on a null object reference`).
- **The consent screen needs an Activity.** `AuthorizationClient` answers with a `PendingIntent`
  that only an Activity can start for a result, so `platform/AndroidAuthorizationHost.kt` bridges
  `MainActivity`'s `StartIntentSenderForResult` launcher to the connect flow — the same shape, and
  for the same reason, as `AndroidFilePickerHost`. With no Activity attached the launch resolves to
  `null`, which is the correct background outcome.

`appDataFolder` is scoped **per Cloud project, not per OAuth client**, so adding an Android client
alongside the existing desktop one leaves both devices reading and writing the same hidden folder. To
re-check it, list `appDataFolder` from a second OAuth client in the same project: the file another
client created there is visible. Without that property, desktop and Android would silently sync to
separate files.

### Google Drive on Apple (secretless "iOS"-type OAuth client)

The Apple app (`:shared`'s appleMain, covering macOS and iOS) goes through the ordinary
`OAuthConnectFlow` + `CustomUriRedirectTransport` path — unlike Android, it does not need Play
services or any device-side authorization API — but with its own OAuth client rather than
desktop's.

- **Why desktop's client cannot be reused.** Desktop's "Desktop app" client requires a loopback
  redirect and a `client_secret` (see "Google Drive (desktop only) — Loopback" above); shipping that
  secret inside a native app binary would let it be extracted from the binary itself. Google's
  policy answer for a native Apple app is the **"iOS" application type**: it takes no secret at all
  ("iOS" also covers a native macOS app — see [build.md](build.md)), and Google's token endpoint
  accepts a `client_secret`-less token exchange/refresh for this client type, unlike "Desktop app"
  clients (which reject it with `invalid_request: client_secret is missing` even with PKCE — see
  above). `GoogleDriveAuthManager.clientSecret` is nullable for exactly this: desktop passes its
  secret, the Apple app passes `null` and the parameter is omitted from the request entirely.
- **Redirect URI.** An "iOS"-type client's only valid redirect is its own reversed-client-id custom
  scheme, not the shared `keryx://oauth2/callback` Dropbox/OneDrive use:
  `NNNN-xxxx.apps.googleusercontent.com` → `com.googleusercontent.apps.NNNN-xxxx:/oauth2redirect`.
  `googleIosClientRedirectUri(clientId)` (`data/cloud/GoogleDriveAuthManager.kt`) is the pure
  function that derives it, and `ApplePlatformModule.kt`'s `appleGoogleDriveProvider` passes the
  result as `CustomUriRedirectTransport`'s `redirectUri`, reusing the same shared
  `MutableSharedFlow<OAuthCallbackParams>` `cloudSessionSingles` already wires up for Dropbox/
  OneDrive — the flow is disambiguated by `state` regardless of which scheme delivered the redirect.
  The Swift app never registers this scheme (or `keryx://`) in its Info.plist: it opens an
  `ASWebAuthenticationSession` instead of the OS routing a custom-scheme redirect, so the session's
  own `callbackURLScheme` parameter is all that's needed — see "AuthorizationLauncher" below.
- **Separate client, same Cloud project.** The Apple client is a distinct OAuth client from
  desktop's, exactly like the Android case above, but obtained by hand in the Cloud Console rather
  than matched by package signature — Google's "iOS" client type has no signature-matching
  equivalent to Android's SHA-1 check, so the client id *is* read from a build-time constant
  (`AppleBuildConfig.GOOGLE_DRIVE_CLIENT_ID`, generated the same way as `DesktopBuildConfig`'s, into
  `appleMain` only — see [build.md](build.md)). It must live in the same Cloud project as the
  desktop/Android clients, for the same `appDataFolder`-is-per-project reason documented above; a
  separate project would leave the Apple app reading and writing a different hidden folder than
  every other device.
- **Availability gate.** `CloudStorageAvailability.apple.kt`'s `googleDriveAvailable` is
  `AppleBuildConfig.GOOGLE_DRIVE_CLIENT_ID.isNotEmpty()` — the same "empty id hides the option"
  convention as every other provider/platform pair, so a build with no Apple client configured
  simply doesn't offer Google Drive, the same way an unconfigured Dropbox key hides Dropbox.
- **Tokens and refresh.** Unlike Android's Play-services path, the Apple app owns a real refresh
  token, stored through `KeychainTokenStorage` like Dropbox/OneDrive — its lifecycle is the ordinary
  `CloudSession.CredentialLifecycle.OAuth`, not the Play-services-style `PlatformOwned` one.

### OAuth authorization on Apple (`ASWebAuthenticationSession`)

Every provider on the Apple app runs the same `OAuthConnectFlow` + `OAuthRedirectTransport` pairing
as desktop (custom URI scheme for all three: Dropbox/OneDrive on `keryx://`, Google Drive on its
own reversed-client-id scheme). What differs is **how the authorize URL gets opened and how the
redirect comes back** — the Apple app never registers a custom URI scheme with the OS and never
opens a browser directly:

- `OAuthConnectFlow` opens the authorize URL through an injected `domain/AuthorizationLauncher`
  (`fun interface { fun launch(authorizeUrl: String, redirectUri: String) }`) rather than calling
  `platform/BrowserOpener` itself. `domain/DefaultAuthorizationLauncher` — the default on every
  platform — opens it in the system browser.
- `KeryxSdk.start`'s `openAuthorization: ((url: String, callbackScheme: String) -> Unit)?` parameter
  lets Swift supply its own launcher. When set, `KeryxSdk` wraps it into an `AuthorizationLauncher`
  that derives `callbackScheme` from the connect flow's own `redirectUri` via `domain/schemeOf`
  (everything before the first `:`) and hands both to the closure. The Swift app opens an
  `ASWebAuthenticationSession(url:callbackURLScheme:)` with them — no Info.plist `CFBundleURLTypes`
  entry is needed for any provider, since the session receives the callback URL directly rather
  than the OS routing it through a registered scheme.
- The session's completion handler passes its callback URL to `KeryxSdk.handleOAuthRedirect(url)`,
  exactly like an OS-routed `keryx://` redirect would be on desktop/Android — `OAuthConnectFlow`
  itself cannot tell the two apart, since both arrive through the same
  `MutableSharedFlow<OAuthCallbackParams>` disambiguated by `state`.
- If the user cancels the session, Swift cancels the connect flow's own coroutine (its `Task`);
  `OAuthConnectFlow` has no separate cancel path of its own to call.

### Nextcloud (Login Flow v2)

Nextcloud is not reached through OAuth and has no app-only folder; everything about it lives in
`data/cloud/nextcloud/`.

- **The server.** The user enters the server's address in a dialog
  (`presentation/settings/NextcloudServerController`, shared by both UIs and every connect route:
  setup, connect, switch, reconnect). `normalizeNextcloudUrl` adds `https://` to an address with no
  scheme, rejects any other scheme (`http://` included) and any address carrying credentials, a query
  or a fragment, and cuts a URL pasted from the browser back to the server's base (`…/index.php/…`,
  `/login`, trailing slashes). `NextcloudServerProbe` then requires `GET {base}/status.php` to answer
  200 with `installed: true`, without following redirects — a 3xx counts as "no Nextcloud here". Each
  failure has its own `ErrorKind` (HTTPS required / not found / unreachable), carried by
  `NextcloudServerException`. Only certificates the OS trusts are accepted. A reconnect starts the
  dialog from the stored server (`CloudSyncController.reconnectServerUrl`, read before the reconnect
  clears the credentials). Choosing Nextcloud while another provider is connected asks for the server
  only after the switch is confirmed, and cancelling the dialog leaves the current connection alone.
- **Sign-in (`NextcloudLoginFlow`).** `POST {base}/index.php/login/v2` returns a sign-in page and a
  poll token/endpoint. The page opens through a `domain/LoginPageLauncher` — the system browser on
  desktop and Android (`BrowserLoginPageLauncher`), an `ASWebAuthenticationSession` with no callback
  scheme on Apple (`KeryxSdk.start`'s `openLoginPage` / `closeLoginPage`). The flow then posts the
  token to the poll endpoint every second (`NEXTCLOUD_LOGIN_POLL_INTERVAL_MS`): 404 until the user has
  granted access, then 200 once with `server`, `loginName` and `appPassword`. The server forgets the
  request after 20 minutes (`NEXTCLOUD_LOGIN_TIMEOUT_MS`); a failed poll in between is simply retried.
  `loginName` need not be the account's user id, which the WebDAV paths need, so the id is read from
  OCS `GET /ocs/v1.php/cloud/user` (`ocs.data.id`). The base, the sign-in page, the poll endpoint and
  the granted `server` must all be HTTPS; an app password the flow cannot finish with (no user id) is
  revoked again. However the flow ends, `LoginPageLauncher.close()` runs once the page was opened:
  Apple cancels the session, desktop brings its window back to the front (a browser tab cannot be
  closed from outside), Android starts its own launch intent with `CLEAR_TOP` so its `singleTask`
  task returns over the Custom Tab. While the sign-in is pending, both UIs say it is waiting for the
  browser, since it can take up to the full 20 minutes. The User-Agent names the device to the server
  (`Keryx (Android)`, …), which becomes the app password's name in the account's "Devices & sessions".
- **Storage (`NextcloudStorage`).** The sync files live in `/Keryx/` under the user's own files
  (`{server}/remote.php/dav/files/{userId}/Keryx/keryx-sync.json`, …); every request carries the app
  password as HTTP Basic credentials and is refused unless the stored server URL is HTTPS. Redirects
  are not followed. `metadata` is a Depth-0 `PROPFIND` for `getetag` (matched by local name, whatever
  the namespace prefix; 404 → absent), `download` a `GET` whose `ETag` is the revision, `upload` a
  `PUT` with the expected revision passed through verbatim as `If-Match`, and `list` a Depth-1 `PROPFIND` for
  `getetag` and `getcontentlength`.
- **Revisions.** The revision is the file's ETag, which Nextcloud compares byte for byte, quotes
  included. An `If-Match` that does not match (or names a file that is not there) answers 412 — with no expected
  revision the `PUT` sends no `If-Match` at all and writes unconditionally — and a concurrent overwrite holding the
  file's lock 423 — both are `SyncConflictException`. The written revision is read from the write's
  own `OC-ETag` / `ETag` header (`headerRevOrConflictOr` in `CloudStorage.kt`), never from a follow-up
  request.
- **Create without overwriting.** Nextcloud does not apply `If-None-Match: *` atomically, so it cannot
  guard the first upload. `create` instead makes sure `/Keryx` exists (`MKCOL`; 405 means it already
  does), uploads to a temporary `/Keryx/.upload-<uuid>.tmp`, then `MOVE`s it onto the real name with
  `Overwrite: F` — an existing destination answers 412 (a conflict) instead of being overwritten, and
  one another write holds locked answers 423 (likewise a conflict), and
  the window in which two devices can both believe they are creating the file shrinks from the whole
  upload to the move itself. A 423 from the `MKCOL` or the temporary upload (another write holding a
  lock on `/Keryx` or that path) is a conflict too, left to the sync's retry rather than reported as a
  storage error. The temporary file is deleted again whenever the move does not happen. One
  whose delete also fails stays in `/Keryx`; its name never matches the sync file pattern, so the listing
  ignores it. It is not cleaned up later, which is accepted.
- **Rename.** `MOVE` with `Overwrite: F` and a full-URL `Destination`: a missing source (404) counts as
  done, and an occupied destination (412) is a storage error.
- **Revocation.** The app password does not expire, so the provider's lifecycle is `Static`;
  disconnecting calls `DELETE /ocs/v2.php/core/apppassword` (with `OCS-APIREQUEST: true`), and the
  local credentials are cleared whether or not that succeeds.
- **Scope.** The app password reaches the whole account, not just `/Keryx` — Nextcloud has no
  app-folder scope like the other providers'. The connect dialog and `PRIVACY.md` say so, along with
  how to revoke it and, on Nextcloud 36 or later, how to limit it to file access.
- **Side effects on the server.** Every upload adds a version (the files_versions app) and an Activity
  entry; a desktop Nextcloud client that syncs the user's files also downloads `/Keryx` unless it is
  excluded there. `PRIVACY.md` and the README carry the user-facing guidance.
- **Local network on Apple.** Reaching a server on the local network shows iOS's (and macOS 15's)
  local-network permission prompt, whose text is `NSLocalNetworkUsageDescription`, localized through
  `InfoPlist.xcstrings` (see "String Catalog for the Apple app" in [build.md](build.md)).

### Token Storage

**Per-provider separate `TokenStorage` instances** are constructed in DI (`platformModule`) (do not share a single instance across providers; `SecurityCliTokenStorage`/`KeystoreTokenStorage` cache results per instance, so sharing would break). Keychain account name and fallback file name are derived from `CloudStorageType.id` (`"dropbox"`, `"google_drive"`, `"onedrive"`, `"nextcloud"`).

**Stored format.** Every store holds the provider's `CloudCredentials` (`data/cloud/CloudCredentials.kt`) as JSON through `CloudCredentialsSerializer`, which picks the shape from the keys (an `appPassword` key means `AppPasswordCredentials`, anything else `OAuthTokens`) and writes no class discriminator. `OAuthTokens` is therefore stored exactly as it was before `CloudCredentials` existed, and tokens saved by an older build keep loading. Do not switch to the default sealed-class serializer: it requires a `"type"` key, so every stored token would fail to decode and the user would silently find the provider disconnected.

**Credential lifecycles.** Each `CloudSession.Provider` says how its stored credentials stay valid and are revoked through a `CloudSession.CredentialLifecycle`. `OAuth` (Dropbox, OneDrive, and Google Drive on desktop and Apple) renews an expired access token with the stored refresh token, saves the renewed tokens, and revokes the access token on disconnect. `PlatformOwned` (Google Drive on Android) asks the platform for a fresh access token on every request, never refreshes or saves anything, and revokes the stored access token on disconnect — see "Google Drive on Android". `Static` (Nextcloud) hands out credentials that never expire (an app password) exactly as stored and calls the provider's own revoke on disconnect — for Nextcloud, deleting the app password on the server; see "Nextcloud (Login Flow v2)".

`KEYCHAIN_SERVICE` (`works.merc.keryx`, `data/cloud/KeychainCoordinates.kt`) is shared by every desktop OS.

- Windows/Linux: OS secure storage (java-keyring — Credential Manager / Secret Service, `KeyringTokenStorage`).
- macOS (the Compose build, internal verification only): Delegated to Apple-signed `/usr/bin/security` CLI (`SecurityCliTokenStorage`). java-keyring fails to write to Keychain from a shared JVM, so the Compose macOS build uses `security` instead. Its items live in the ordinary login Keychain — the native Apple app below uses the same service name, but a different store.
- Native Apple app (macOS/iOS, `:shared`'s appleMain): `KeychainTokenStorage` writes the Keychain
  directly through the Security framework, service `works.merc.keryx`, same per-provider account for
  every provider — but into the **Data Protection Keychain**
  (`kSecUseDataProtectionKeychain`, gated on the shipping app's `keychain-access-groups`
  entitlement) rather than the ordinary login Keychain `security` reads and writes, so it is a
  separate store the Compose build cannot reach at all even though the service and account match.
  That separation depends entirely on `KeryxSdk.start`'s `useDataProtectionKeychain = true`; its
  default `false` (for tests) would share the Compose build's login-Keychain items. There is no
  plaintext fallback (a failed write is `NOT_PERSISTED`). No migration from the Compose build's own
  items is attempted — the native app always starts by reconnecting, and synced data comes back
  from the cloud. Google Drive is offered there when `AppleBuildConfig.GOOGLE_DRIVE_CLIENT_ID` is
  configured (and hidden when it is empty); see "Google Drive on Apple (secretless "iOS"-type OAuth
  client)" above.
- Linux, inside the Snap package specifically: `LibSecretTokenStorage` instead of `KeyringTokenStorage`, gated on `platform.isSnap`. It calls libsecret directly via JNA, which detects the sandbox and routes through the Secret portal (`org.freedesktop.portal.Secret`) instead of raw Secret Service, encrypting the token JSON in a local file with a per-app master secret obtained from that portal — the snap declares no `password-manager-service` plug at all (Snapcraft reviewers decline auto-connect for that interface on principle, and nothing here would use a manually-connected one anyway, since `KeyringTokenStorage` is unreachable from inside the snap by design). See `build.md`'s "Linux Snap package" for the full reasoning; not applied outside the snap, so existing deb/rpm users' Secret Service items are unaffected.
- **Fallback file and outcome reporting**:
  - On failure for any of the above, fallback to a file in the data directory
    `.{CloudStorageType.id}_tokens.json` (0600. Dropbox is `.dropbox_tokens.json`). DI selects
    between the three using `isMacOs`/`isSnap` (`providerTokenStorage` in
    `PlatformModule.desktop.kt`).
  - `TokenStorage.save()` reports where the tokens actually ended up — `TokenSaveOutcome.SECURE`
    (the secure store, or Android's Keystore-encrypted file), `PLAINTEXT_FILE` (readable in the
    fallback file — either because the secure store could not be reached, or because a secure
    write succeeded but a stale fallback copy could not be deleted afterwards), or `NOT_PERSISTED`
    (neither accepted the write, so the tokens live only until the app exits and the account has to
    be connected again afterwards).
  - **Unpersisted tokens are held in memory.** On `NOT_PERSISTED`, `CloudSession` (a process-wide
    singleton) keeps that provider's tokens in memory and reads them in preference to the store —
    for the connected check, the access-token supply, its refresh, and the revoke on disconnect — so
    the session keeps syncing until the app exits. Without it, the connect would report success
    while every sync was skipped as "not connected". A later save that reaches a store drops the
    in-memory copy, as does a disconnect.
  - **Token writes are serialized with disconnect.** `CloudSession` runs the refresh-and-save, the
    initial save after a connect, and the disconnect's revoke-and-clear under one mutex. Otherwise a
    disconnect landing while a refresh waits on the network would clear the store, and the refresh
    would then save its new (and never revoked) tokens back — on disk, or in the in-memory copy above.
    The lock covers only that token work, never the cloud request that uses the returned token.
  - `CloudSession` raises a coalesced `WARNING` notification (with a `ShowInfoDialog` cause-and-fix
    action) for each of the two degraded outcomes, with its own message for each, on the initial
    connect and on a background token refresh alike. The fallback is still allowed (it is the
    documented degrade-gracefully path, see `SECURITY.md`); it just must not be silent — and a save
    that persisted nothing must not be reported as one that landed in a file.
  - **Clear-on-success.** On a successful secure write, every desktop secure-store backend also
    clears a stale fallback file left over from an earlier degraded save, so a plaintext copy
    doesn't keep sitting on disk once secure storage starts working again — the same clear-on-success
    behavior `KeystoreTokenStorage` uses on Android (see below). `KeyringTokenStorage`/`LibSecretTokenStorage`
    share this composition logic via `SecretStoreTokenStorage`, and so does `SecurityCliTokenStorage`,
    whose `SecurityKeychainStore` backend inherits it and which only adds an in-memory cache on top. A fallback clear
    that does not confirm removal downgrades the outcome to `PLAINTEXT_FILE` rather than falsely
    reporting `SECURE`.
- The Compose macOS build (internal verification only) performs a **read-back verification** after writing (explicitly specifying login keychain), and falls back to file if persistence cannot be confirmed. **Write persistence is session-dependent**: packaged version (GUI login session) persists to login keychain, but `gradlew run` (detached session under Gradle daemon via launchd) may not persist even if `security add` returns success, so file is used. **Read is possible from either session** (once linked via packaged version, `gradlew run` can also reuse the connection).
- **Android**:
  - **Google Drive is the exception to everything in this section.** Its tokens are owned by Play
    services, not by this app (see "Google Drive on Android" above): no refresh token is ever
    stored, and the access token that *is* written here exists only so a restart still reports the
    account as connected — the tokens actually used for requests are fetched from Play services on
    demand. Everything below applies to it in mechanism (same `KeystoreTokenStorage`, same fallback
    and outcome reporting) but guards a far less sensitive secret: a one-hour access token rather
    than a long-lived refresh token.
  - **Encryption.** `KeystoreTokenStorage` encrypts the token JSON with an AES-256/GCM key held in
    the Android Keystore (one key alias per provider, derived from `CloudStorageType.id`; the key
    material itself never leaves the Keystore/TEE where the device supports it) and writes
    `IV || ciphertext` to `.{CloudStorageType.id}_tokens.enc` under `Context.filesDir`. The key is
    created with `setUserAuthenticationRequired(false)` — the periodic `WorkManager` background
    sync must be able to decrypt tokens with the device locked, unlike a typical per-transaction
    secret.
  - **Decryption failure and fallback.** Treated exactly like "nothing saved" rather than surfaced
    as a crash (Keystore reset, a device/OS restore that cannot carry hardware-backed keys), and
    the unreadable file is deleted so it doesn't linger; a device with no usable Keystore at all
    falls back to the same plaintext `FileTokenStorage` desktop uses as its last resort.
  - **Clear-on-success.** A successful encrypted save also clears that fallback file, and takes its
    `SECURE`/`PLAINTEXT_FILE` answer from what the clear itself reports: `TokenStorage.clear()`
    returns `TokenClearOutcome.CLEARED` or `DATA_MAY_REMAIN`, and `FileTokenStorage` decides that
    from whether the file is still on disk after the delete attempt (a `File.delete()` that
    returned false leaves it there). So a plaintext copy that survives is reported as
    `PLAINTEXT_FILE` rather than `SECURE`, and gets warned about instead of sitting readable on
    disk unnoticed.
  - **Why the check is file existence, not `fallback.load()`.** `FileTokenStorage.load()` reports a
    file whose JSON no longer decodes as "nothing stored", while the refresh token inside it stays
    perfectly readable — so inferring the cleanup from `load()` claimed `SECURE` for exactly the
    case that most needed the warning.
  - **Backup exclusion.** Both the `.enc` and the plaintext-fallback `.json` files are excluded
    from Android's auto backup/device-transfer (`AndroidManifest.xml`'s
    `dataExtractionRules`/`fullBackupContent`) — a long-lived OAuth refresh token (or Nextcloud's
    app password, which reaches the whole account) should never ride along in a backup, and the Keystore-encrypted file couldn't be usefully restored to a different
    device anyway.

### Future Work (not yet supported)

- **file → Keychain migration heal**: If `.dropbox_tokens.json` exists but Keychain is empty, `SecurityCliTokenStorage.load()` would write the file value back to Keychain and **delete the file only if read-back verification succeeds** (on failure, keep the file to prevent data loss). Not yet implemented — `load()` currently only falls back to the file, without writing back.

## Sync Target Article Range

Startup cache cleanup **soft-deletes** articles past the retention period (stamps `deleted_at` / `deleted_updated_at`)
rather than physically removing them. The tombstones travel as light rows and propagate via the merge
(the retention group's last-write-wins), so a device that missed the deletion converges instead of re-adding the
article. Tombstones are not physically reclaimed yet (future work).

Cleanup decides by `cached_at`, the last time an article was seen in its feed. The merge still keeps the newest
value, but a delta carries a `cached_at` change only when its day changes. A device that does not refresh a feed
itself may therefore see another device's `cached_at` up to about a day late, and so clean up an unstarred article at
the edge of the retention period up to a day earlier than before — and that deletion propagates. Starred articles
and the latest 10 per feed are kept regardless, and convergence is unaffected.

## Migration from the Pre-Delta Format

The pre-delta format is a single full database at `/keryx.db.gz` (and, before compression, `/keryx.db`). A device
running this version that finds no pointer but a `keryx.db.gz`:

1. Merges `keryx.db.gz` as before (validated, migrated to the current schema, `MergeSql`) and remembers its
   revision. The merge runs with capture as it stands (no `sync_merging`): on a device that has not joined yet nothing
   is recorded, and the full send of step 2 carries the rows; on a device that already sent into its own generation
   (an earlier attempt interrupted before creating the pointer) the rows it changes enter the outbox and follow as a
   delta.
2. Joins: its full send becomes the first base, and it creates the pointer. If a pointer already exists (another
   device migrated first), this is an ordinary join.
3. Uploads the downloaded file as `keryx-YYYYMMDD-HHMMSS.db.gz.bak` (`create`; a name already taken in that second —
   possibly by different bytes — moves to `keryx-YYYYMMDD-HHMMSS-1.db.gz.bak`, `-2`, …; any other
   failure ends the sync before step 4, and the next one tries again) — a one-time manual way back.
4. Replaces `keryx.db.gz`, **with a revision check against step 1's revision**, by an empty SQLite file whose
   `user_version` is 1000. It is not renamed away first: between checking it and renaming it, an older build could
   write data that would then be archived without ever being merged.
   - Revision mismatch → download and validate it. A stub (another device got there first) → record its revision;
     done. A full database (an older build wrote) → merge it **with capture on** (no `sync_merging`), so every row
     it actually changes enters the outbox and reaches every device as a delta; then retry step 4.
   - Success → record the revision as `ks_legacy_stub_rev`.
5. An older build that reads the stub raises `SchemaVersionException` ("update the app") before writing anything:
   it fails visibly instead of silently diverging. (Its `DatabaseMerger.merge` reads the downloaded file's
   `user_version` before it opens the local database — `migrateCloudIfOlder` on every platform.)

The stub is recognized by content, not by name or size: `user_version` 1000 and no tables. A file of any other newer
version — 1000 with tables in it included — is a `SchemaVersionException` like any too-new cloud file. A stub found
with no pointer (the pointer removed by hand, say) means there is no pre-delta data: its revision is recorded and the
device starts as the first one.

**Interruptions.** Every step is decided again from the cloud, so a crash anywhere converges on a later sync: before
the pointer exists, the next sync finds no pointer and a full `keryx.db.gz` and starts over (the merge is idempotent; a
send already recorded is resumed and its pointer created, step 0 and step 1 of "Sync Flow"); once the pointer exists,
the legacy watch finds a full `keryx.db.gz` whose revision is not `ks_legacy_stub_rev` and finishes steps 3–4; after
the stub's upload but before its revision is recorded, the watch downloads the stub and records it. The archive of
step 3 is made by whichever runs it — the migration or the legacy watch — **while this device has no stub revision
recorded**: until then the full database is the original pre-delta data (an interrupted migration's, or one another
device left half done). A full database found after a stub was known is an older build's later write; it is merged
but not archived. A stub replacement that loses its revision check `SYNC_MAX_RETRY` times in a row (the file kept
changing) is a transient failure; the next sync continues.

**An unusable `keryx.db.gz`.** A `keryx.db.gz` that fails validation (corrupt, or a full database that cannot be
merged) fails the migration and the legacy watch alike with `CloudDataIncompatibleException`, on every sync. The
notification center's reset is the way out: it archives the file and puts the stub in its place (see "Resetting
(Archiving) Cloud Data").

**An older build writing after the migration.** On Dropbox, OneDrive and Nextcloud its revision-checked upload
fails, and on retry it reads the stub. On Google Drive the revision check is not atomic, so it can overwrite the
stub; the legacy watch (step 1 of "Sync Flow") notices the changed revision and absorbs it exactly as in step 4,
then puts the stub back. If an older build's overwrite and the stub's replacement land at the same moment on Drive,
that one write of the older build can be lost — the same cause as "Google Drive: an upload can overwrite another
device's upload unnoticed" in [known-issues.md](known-issues.md).

A pre-delta database holds columns the current schema no longer syncs: migrating it to schema 3 moves its fetch
state into `feed_fetch_state` inside the temporary file only (never into the local database), initializes the
per-field stamps and `articles.body_updated_at` from `updated_at`, and its articles' `search_text` is recomputed as for any merge.

Builds older than compression (reading only `/keryx.db`) are unaffected by the stub and keep the one-way divergence
documented for them: this version neither reads nor writes `/keryx.db`. The legacy watch and this migration are
removed at v1.0.0.

## Resetting (Archiving) Cloud Data

`SyncRepository.resetCloudData()` is the recovery path for cloud data the app cannot use. It deletes nothing: the
device joins a **new generation**, uploads its local full state as that generation's base, and replaces the pointer
with a revision check (a mismatch — another device reset first — aborts and re-reads the pointer). The previous
generation's files stay in place, unread, as the archive. Every other device sees the new generation on its next
sync and rejoins with its own full send, so data that only another device held comes back.

Once the pointer is replaced, the reset turns to the pre-delta `keryx.db.gz`, so that one the legacy watch cannot
use (corrupt, a full database failing validation, one that cannot be read) stops failing every sync:

1. Absent → nothing to do. Its revision equals `ks_legacy_stub_rev` → nothing to do.
2. Otherwise download it. A download failure (other than the file having gone) fails the reset as a transient
   failure — the file is never replaced without having been archived.
3. The stub → record its revision as `ks_legacy_stub_rev`; done.
4. Anything else → upload its raw, still-compressed bytes as `keryx-YYYYMMDD-HHMMSS.db.gz.bak` (`create`; a name
   already taken moves to the next `-N` suffix, any other failure fails the reset), then replace `keryx.db.gz` by the stub **with a revision
   check against the revision just downloaded**. Success → record the stub's revision. A mismatch (the file changed
   in between) → back to step 1, at most `SYNC_MAX_RETRY` times, after which the reset fails transiently.

Nothing in it is merged: a reset replaces the cloud data by this device's state, and the archive keeps the file
recoverable by hand. Existing `.bak` archives and a valid stub are left alone. Each step is decided again from the
cloud, so a crash anywhere converges: before the stub's upload, the legacy watch still fails the next sync and the
next reset finishes the job (archiving again under its own name); after the upload but before its revision is
recorded, the next sync's legacy watch downloads the stub and records it.
