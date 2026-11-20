# Known Issues

[日本語](known-issues.ja.md)

Defects that are understood but deliberately not fixed, with the evidence behind that decision.
Each entry records what was ruled out, so a later investigation doesn't repeat the same work.

An entry needs a reason other than low likelihood: the root cause is external (an OS, JDK, library or
platform limitation), or closing the hole needs a redesign outside the scope of the change that found it.
"It rarely happens" or "the window is too narrow to hit" is never enough — such a defect is fixed
(see "Error handling and race conditions" in `.claude/CLAUDE.md`). Every entry whose cause is known
states what a real fix would need — which may be a change upstream.

## macOS: clicking a notification banner does not restore a tray-hidden window

**Status**: not fixed — external (JDK/AWT) limitation. Applies to the **Compose macOS build only**,
which is kept for internal verification and not released; the shipped macOS app is the native
SwiftUI app (`external-spec.md` §2), which is not affected. The tray icon click and relaunching the
app (single-instance forwarding, e.g. clicking the Dock icon while running) both restore the window,
so it is never unreachable; only the banner's own click-through fails, and only while tray-hidden.

### Symptom

With the window hidden to the tray on macOS, clicking a new-article desktop notification does
nothing at all — no window, no Dock icon, no sound. The banner's "Show" action button dismisses
the banner and likewise does nothing else. When the window is merely backgrounded (visible but
behind other windows), the same click brings it to front.

### Diagnosis

AWT's `TrayIcon.addActionListener(...)` is the only API `TrayIcon` offers for a click on a
`displayMessage(...)` banner, and logging on a real packaged build showed **the `ActionListener` is
never invoked** when the click happens while the window is tray-hidden (Accessory activation
policy). The restore logic itself (`main.kt`'s `activationRequests` collector) is fine: reached via
the tray-icon click or a forwarded second launch, it restores the window every time.

The likely cause is `TrayIcon`'s native macOS peer (`CTrayIcon`), which bridges
`displayMessage(...)` through the deprecated `NSUserNotification` API; its click delegate callback
(`userNotificationCenter:didActivateNotification:`) apparently is not bridged back to Java while
the app has no Dock icon (`NSApplicationActivationPolicyAccessory`). Narrowing it further would
need decompiling `CTrayIcon` or a minimal pure-AWT reproduction, neither of which has been done.

The backgrounded case working is macOS's own default click-to-activate behavior for a
Regular-policy app, not app code: it behaves the same with no notification-click wiring at all. So
`MacTray` registers no `ActionListener` and has no notification-click handling of its own. Linux's
notification-click handling (`LinuxTray`, via `LinuxNotifier`'s D-Bus `ActionInvoked` signal) is
separate and works; `KeryxTray`'s `onNotificationClicked` parameter and `main.kt`'s
`activationRequests.tryEmit(Unit)` callback exist for it.

### Ruled out

- **A bug in `main.kt`'s restore/activation-policy logic** — the same `activationRequests`
  collector restores the window correctly when reached via the tray icon or a forwarded launch.
- **Ordering of the Cocoa activation-policy promotion vs. showing the window** — promoting to
  Regular and activating first, with showing/fronting/focusing deferred a further EDT turn, made no
  difference to the notification click. The ordering stays in `main.kt` because it is what makes
  restore-from-tray via the single-instance/reopen path work.

### Workaround

None applied — the tray icon click and relaunching the app are the supported ways to bring a
tray-hidden window back on macOS.

### What a real fix would need

Bypass AWT's `TrayIcon.displayMessage()`/`ActionListener` on macOS and drive
`NSUserNotificationCenter` (or the non-deprecated `UserNotifications` framework) directly through a
JNA-based Objective-C bridge — like `MacActivationPolicy`'s raw `objc_msgSend` calls, but much
larger: it needs a runtime Objective-C class (`objc_allocateClassPair`/`class_addMethod` with a JNA
`Callback` as the implementation) as the notification center's delegate, which is high-risk native
interop (a mistake can crash the JVM) and needs several real-hardware iterations.

It will not be built: the affected Compose macOS build is not released, and the shipped SwiftUI app
gets notification clicks through `UNUserNotificationCenterDelegate` in the ordinary app lifecycle.

## Article list crashes the UI thread during heavy scroll + selection churn

**Status**: not fixed — upstream Compose defect, waiting on a library update.

### Symptom

While the article list (middle pane) is being scrolled, the AWT event thread dies with:

```text
java.lang.IllegalArgumentException: onReuse is only expected on attached node
    at androidx.compose.ui.node.LayoutNode.onReuse(LayoutNode.kt:2262)
    at androidx.compose.runtime.Applier.reuse(Applier.kt:185)
    ...
    at androidx.compose.ui.layout.LayoutNodeSubcompositionsState.subcompose(SubcomposeLayout.kt:719)
    at androidx.compose.foundation.lazy.LazyListMeasureKt.measureLazyList-pIk1_oM(LazyListMeasure.kt:179)
    at androidx.compose.foundation.lazy.LazyListState.onScroll$foundation(LazyListState.kt:549)
```

The same exception is logged three times (captured in composition → `SEVERE [Main] Uncaught
exception in window` → `Exception in thread "AWT-EventQueue-0"`); it is the *first* error, not a
knock-on failure from an earlier one.

After it fires the window stops responding. **No data is lost** — the crash is confined to the UI
layer and never reaches the DB write path. Recovery is restarting the app.

### What triggers it

`LazyColumn` recycles the composition of rows that scroll out of view. The crash needs **two**
things at once:

1. The article list's scroll-into-view effect running — `ArticleListPaneContent`'s
   `LaunchedEffect(listState, revealRequest, …)` → `scrollToIndexIfNeeded` (`ui/home/HomeCommon.kt`).
   Of its keys only `revealRequest` changes during use: it counts the selections the user moves to another article
   (`HomeViewModel.selectionRevealRequests`). The selection's id changing on its own — a selection
   found tombstoned hands the cursor back to the article before it — does not re-key it.
2. A row with enough layout nodes. `ArticleRow`'s current structure is past the threshold.

Measured with an automated harness (see below), the crash needs the selection change and the wheel
event to alternate **15 or more times**. It is sharply deterministic around that point:

| Interleaved selection changes | Reproduced |
| --- | --- |
| 14 | 0 / 5 |
| 15 | 5 / 5 |
| 20, 60 | 5 / 5 |

In terms of real gestures, that means one of:

- **Holding ↓ / ↑ / J / K** (the OS key auto-repeat fires ~25–30 times a second, so 15 repeats is
  roughly half a second) while the wheel or trackpad is also scrolling.
- Pressing those keys **while macOS inertial scrolling is still running** — a flick keeps emitting
  wheel events for over a second after the fingers lift, so the two overlap without the user doing
  anything deliberately simultaneous. This is the most likely way it is hit in practice.
- Clicking through articles very rapidly while scrolling. A click on a row that is only
  *partially* visible at a viewport edge also scrolls (`scrollToIndexIfNeeded` is a no-op only when
  the row is fully in view); right-click counts too, since it selects the row as well.

### What does *not* trigger it

Verified not to reproduce, so "it crashed while I left it alone" is not this bug:

- A **single** selection change, even with the wheel turned during its scroll animation (0 / 5).
- The list going from empty to non-empty — startup restore, or a refresh landing with unread-only
  on (0 / 5).
- Background feed refresh, cloud sync, cache cleanup, read/unread and star toggles, and
  "mark all read". None of these is a selection the user makes, so the effect never re-keys.

### Cause

An internal invariant violation inside Compose's own lazy-list item recycling: the `LayoutNode`
pulled from the reuse pool has already been detached when `onReuse()` asserts it is attached. No
misuse of the Compose API was found on the app side — a programmatic scroll concurrent with a user
scroll is legitimate and should not corrupt the reuse pool.

The bug is **latent and sensitive to the number of layout nodes per row**, not to anything specific
the row does. This was established by taking the article card's *previous* metadata line (a single
joined `Text`) and wrapping it in the same extra `Row`/`Spacer` nesting the current one uses: that
alone reproduces the crash. So the metadata-line change that preceded the first report did not
introduce the defect — it only pushed the row past the threshold that exposes it.

Upstream reports of the same assertion:
[compose-multiplatform#3977](https://github.com/JetBrains/compose-multiplatform/issues/3977),
[issuetracker 303256075](https://issuetracker.google.com/issues/303256075).

### Mitigation: fewer nodes per row

Since the bug is node-count sensitive, `ArticleRow`'s node count was reduced without any visible
change: three `Spacer`s (a fixed-gap composable, and therefore its own `LayoutNode`) were replaced
with a leading/trailing `Modifier.padding` on the adjacent element instead (a modifier attaches to
the *existing* `LayoutNode`, so it adds no node), and the favicon's wrapping `Box` was replaced
with a direct `AsyncImage`/`Spacer` choice. This took the row from 12 always-present nodes down to
8 — fewer than the row had *before* the metadata-line change that first exposed this bug (9 nodes).
See "Gaps and node count" in the `ui-guidelines` skill for the pattern; do not reintroduce a
`Spacer` for a plain fixed gap in this row (or any other `LazyColumn` row).

Measured effect: at 12 nodes, `ArticleReuseCrashRepro` reproduced the crash 5/5 with the
selection/wheel alternation threshold at 15. After the reduction to 8 nodes, it no longer
reproduces at all in that harness, even at 10x the original threshold (150 alternations, 3/3
clean; the committed test runs 60, comfortably above the old threshold, also 0/5).

**This is a mitigation, not a fix.** It does not touch the underlying Compose defect, so:

- It is not proof the crash can never happen — only that it takes more provocation than the
  harness's most aggressive tested setting to trigger with the current row structure.
- A future change that adds nodes back to this row (or any other `LazyColumn` row sharing the same
  reuse pool) can reduce or remove this margin again, the same way the metadata-line change did.
- `gutter Box` (the 8dp-wide star/unread-dot container) was **not** touched — removing it would
  need the star icon and unread dot repositioned via manual offsets instead of `Box`/`align()`,
  which was judged too risky for the node it would save.
- The Android row swipe (`ui/home/ArticleRowSwipe.kt`) is built from modifiers on the row's own node
  (`pointerInput`, `drawBehind`, `absoluteOffset`) rather than M3's `SwipeToDismissBox`, which would wrap
  the row in two to three more nodes. Move it to `SwipeToDismissBox` once this defect is fixed upstream
  (see "Standard components over custom drawing" exception 3 in the `ui-guidelines` skill).

### Ruled out

Each of these was tested against the deterministic repro and made no difference:

- The pane's `VerticalScrollbarIfNeeded` sharing the `LazyListState`.
- `Modifier.nativeContextMenu` on the row (its per-row `remember` / effects / `pointerInput`).
- Coil's `AsyncImage` — never composes in the repro, since no favicon is set.
- `stringResource` lookups in the row.
- Compose version skew: `runtime` / `foundation` / `ui` all resolve to a single version, and the
  `org.jetbrains.compose.*` and `androidx.compose.*` desktop artifacts on the classpath are empty
  alias jars, so there is no duplicate-class conflict. The `compose-material3` pin (currently `1.9.0`,
  `gradle/libs.versions.toml`) is also not a factor — re-check the latest stable release before
  ruling this out again on a future bump, per "Re-checking after a library update" below.

### Workarounds that did not work

Listed so they are not tried again:

- Replacing the animated scroll (`animateScrollToItem` / `animateScrollBy`) with the instant
  `scrollToItem` / `scrollBy`.
- Waiting for `isScrollInProgress` to clear before scrolling into view.
- Both of the above combined.
- `requestScrollToItem`, which defers the scroll to the next measure pass.
- Upgrading to a newer Compose Multiplatform pre-release (confirmed to actually resolve in the
  dependency graph; still reproduces 5 / 5 as of the version tested — re-check per "Re-checking
  after a library update" below rather than trusting this version number indefinitely).

The only thing that prevents it is removing the scroll-into-view behaviour entirely, which would
mean the selected article can sit off-screen during keyboard navigation. That is a worse trade than
the crash's low frequency, so it was not taken.

### Re-checking after a library update

A disabled regression harness is kept at
`composeApp/src/desktopTest/kotlin/works/merc/keryx/app/ui/home/ArticleReuseCrashRepro.kt`. It
drives the same desktop code path as a real wheel scroll, so it needs no manual interaction.

**Test against the pre-mitigation structure, not the current one** — per "Measured effect" above,
the current 8-node `ArticleRow` already passes this harness cleanly regardless of whether the
upstream bug is fixed, so running it as-is proves nothing. First revert `ArticleRow`
(`ArticleRowComponents.kt`) to its pre-mitigation structure: `Spacer`-separated gaps and a
`Box`-wrapped favicon, rather than the `Modifier.padding`-folded gaps and `AsyncImage`/`Spacer`
branch currently there (see the inline comment at the favicon branch, and the "cut ArticleRow's
LazyColumn item node count" commit for the exact diff to reverse). Then remove `@Ignore` and run it
a few times:

```bash
./gradlew :composeApp:desktopTest --tests '*ArticleReuseCrashRepro*' --rerun-tasks
```

If it passes repeatedly at this pre-mitigation structure, the upstream fix has landed: delete this
entry (or keep the test as a normal regression test), keep `ArticleRow` reverted to the
pre-mitigation structure, and remove the "Gaps and node count" section from the `ui-guidelines`
skill — it only exists to document this workaround. If it still fails, restore both the `@Ignore`
and the node-count mitigation (revert the temporary change).

## macOS: clicking exactly at a list row's edge selects the neighbouring row

**Status**: not fixed — it is macOS's own behaviour, reproduced with the same aim in Apple's Notes
app. The app-side geometry it was first blamed on was measured and found exact. The genuine
hit-area defects the investigation *did* uncover are fixed — see "What was actually fixed" below.
This entry concerns the **Compose desktop build on macOS** (kept for internal verification; the
shipped macOS app is the native SwiftUI app, `external-spec.md` §2) — the list rows, test and
measurements below are all the Compose ones.

### Symptom

Aim at the very bottom edge of a selected feed / folder / tag / article row — close enough that the
cursor visibly overlaps the selection highlight — and click: the row *below* is selected instead.
Since rows are separated by a 4dp gap, the newly selected row's highlight then begins a visible
distance below where the click appeared to land, which reads as the selection jumping past the
click. Reported repeatedly as "clicking inside the highlight selects the item underneath".

That distance grew when the gap went from 2dp to 4dp (a drag insertion marker no longer fills the
gap, so `LIST_ROW_GUIDE_CLEARANCE` now holds it clear of both highlights — see the `ui-guidelines`
skill). The cause below is unchanged and lives in the OS, so this is a slightly more visible
symptom of the same thing, not a new defect.

### Diagnosis

Both halves of the obvious explanation — "the highlight and the hit area disagree" and "the
pointer coordinates are offset" — were measured directly, and both are exact.

**The highlight matches the band.** A temporary test captured the rendered pane and read back
pixels: at density 2.0 the three article bands were `[48, 126)`, `[126, 204)`, `[204, 282)` — that
is, adjacent bands are contiguous with no unaccounted-for space between them — and the selected
row's highlight painted rows 126..203 of that middle band, i.e. every pixel of it.

**The coordinates match too.** A temporary AWT `MouseMotionAdapter` on the window logged the raw
event Y alongside the Y that Compose's pointer input received for the same motion. The difference
was **0**, at both the top and the bottom of the pane, at density 2.0. There is no offset to
correct.

**What is left is the cursor itself.** macOS draws the arrow cursor as a black glyph with a white
outline, and the hotspot is the tip of the *black* glyph — so the point the eye reads as "the tip"
sits 1–2 physical pixels *above* the real hotspot. Aiming to just touch an edge therefore puts the
hotspot just past it. This matches every observed detail: it happens at the edge and nowhere else,
it is independent of scroll position, and the size of the effect does not change with the gap.

**It reproduces in a native app.** The same aim in Apple's Notes app selects the neighbouring note
the same way. Whatever the exact per-pixel cause, matching it is not a defect in this app.

### What was actually fixed

The hit-area defects found while investigating this are fixed, and `ListRowHitAreaTest`
(`composeApp/src/desktopTest/.../ui/home/`) pins the current geometry:

- **A row's hit area is its whole band**, margin and rounded corners included, while the painted
  highlight is inset and clipped — `listRowClickable` / `listRowSurface`
  (`ui/home/ListRowChrome.kt`) keep the two apart, so no point inside a row selects nothing.
- **The hit boundary between two rows is the gap's midpoint.** The drag insertion marker is painted
  into the rows' own margins (`insertionMarkers`) rather than laid out, so it takes no space from
  either row.
- **A row's band height does not depend on its state** (a folder header collapsed or last, a feed
  row last in its group).

What remains is only the edge case above.

### Ruled out

- **`apple.awt.fullWindowContent` / `transparentTitleBar`.** Suspected of shifting the window's
  content coordinate origin relative to what AppKit hit-tests. Disabled experimentally — the title
  bar came back and the behaviour was unchanged.
- **The feed list's drag machinery.** `FeedListDragController` resolves boundaries and row halves
  for drop placement only; it never calls `selectFilter`. Selection is entirely each row's own
  `listRowClickable`, so `bandAt` / `resolveHitBand` / `resolveRowHalf` cannot influence it.
- **Modifier order, asymmetric padding, and the gap split.** `ListRowHitAreaTest` sweeps 1px at a
  time across each boundary (article↔article, folder header↔first feed, feed↔feed, tag-nested
  feed↔feed) and asserts that adjacent bands are contiguous and that the resolution flips exactly
  at the shared boundary.
- **Shrinking the gap.** Tried at 6dp, 4dp, 2dp, and 0dp (highlight filling the whole band, no
  margin at all). Each step narrowed the window in which the effect occurs but none removed it —
  consistent with the cause being where the click lands, not where the rows are.
- **Compose itself.** Notes is a native AppKit app and behaves the same way, and the cursor
  hotspot is decided by the OS, so nothing here is specific to the Compose build. (The SwiftUI app's
  native lists were not measured for this.)

### Why this is not worked around

Compensating would mean biasing each row's hit area upwards relative to its highlight. That trades
one edge for the other: clicking near the *top* of a highlight would then select the row above.
There is no bias that fixes both edges, and the platform's own apps do not apply one.

## A concurrent write can fail a read-then-write transaction with a non-retryable SQLITE_BUSY

**Status**: not fixed — a real fix needs a purpose-built JDBC driver that issues `BEGIN IMMEDIATE`
(see "What a real fix would need" below), a change to the core data-access layer outside the scope of
the change that found it. Worked around in the one test it made flaky.

### Symptom

Under concurrent writers, `FeedRepository.subscribeFeedWrite`'s `feeds.upsert` can throw
`org.sqlite.SQLiteException: [SQLITE_BUSY] The database file is locked`. This is an *uncaught*
exception on the calling thread, not a `Result.Err` — `subscribeFeedWrite` has no `try`/`catch`
around its `db.transaction {}` block, so the exception propagates past `FeedRepository` entirely.

First observed via a CI-only flake in
`FeedRepositoryTest.subscribeFeedSerializesSortOrderAllocationAcrossConcurrentCalls` (two real
`Thread`s calling `subscribeFeed` concurrently against a file-backed DB), then reproduced locally
(1 failure in 30 runs) with the same stack trace.

### Diagnosis

`subscribeFeedWrite`'s `db.transaction {}` reads before it writes (`feeds.getByUrl` /
`feeds.nextSortOrderInGroup`, then `feeds.upsert`). SQLDelight's `JdbcSqliteDriver` always issues a
plain `BEGIN TRANSACTION` (SQLite's default *deferred* mode), so the transaction starts by taking
only a SHARED lock at the read and has to **upgrade** to RESERVED at the write.

SQLite's own lock-upgrade rule (documented behavior of `sqlite3_busy_handler`) is: if granting the
upgrade could deadlock against another connection that is itself waiting to upgrade, SQLite returns
`SQLITE_BUSY` **immediately, without invoking the busy handler**. `busy_timeout` (this app's
`SQLITE_BUSY_TIMEOUT_MS`, applied via `sqliteConnectionProperties()`) only governs waiting to
*acquire* a lock that has no conflicting upgrade in progress — it does not apply here.

The two overlapping writers in the failing test: the first `subscribeFeedWrite` call holds RESERVED
inside `feedPlacementMutex.withLock { db.transaction { ... } }`, but the *next* thing that
call does after releasing the mutex — `articleRepository.upsertParsed(feedId, fetched.articles)`, a
separate per-feed `db.transaction {}` — can still be mid-flight, still holding a lock, when the
second call's own transaction tries to upgrade its SHARED read lock to RESERVED for its write.

(That is the code as it was observed. A subscribe has since written its articles inside the same
transaction as its feed row, under the mutex, so a refresh cannot commit between the two; the search
index's catch-up still writes after the mutex. The transaction still reads before it writes
(`getByUrl` → `upsert`), so another writer — a refresh, say — can still make its upgrade fail the same
way, and the conclusion below stands: the real fix is `BEGIN IMMEDIATE`.)

### Ruled out

- **`busy_timeout` does not help.** It is set (`SQLITE_BUSY_TIMEOUT_MS = 5_000`), yet the failing
  test run completed in well under a second — no waiting occurred, consistent with a failed lock
  *upgrade* bypassing the busy handler rather than a slow acquisition timing out.
- **Switching to WAL mode would not remove this**, only narrow the window: WAL still serializes
  writers, and a writer that needs to upgrade past another writer's held lock fails with
  `SQLITE_BUSY_SNAPSHOT` for the same underlying reason.

### What a real fix would need

A `ConnectionManager`/`JdbcDriver` that issues `BEGIN IMMEDIATE TRANSACTION` instead of a plain
`BEGIN`, so a transaction that is going to write takes its write lock up front instead of upgrading
into it later — turning the failure mode into an ordinary, retryable "wait for the lock" case that
`busy_timeout` already covers. `app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver` is a
`class` (not `open`), and its transaction begin/end/rollback come from a private
`connectionManager(url, properties)` factory, so this cannot be done by subclassing — it needs a
purpose-built `JdbcDriver` implementation in `desktopMain`, reimplementing
`ThreadedConnectionManager`'s per-thread connection handling and the listener bookkeeping
`JdbcSqliteDriver` itself does. Because every transaction would become a writer up front, this needs
separate evaluation of its interaction with FTS incremental indexing and the sync merge before it
could be applied — out of scope for a CI-flake fix.

### Where this can happen

Confirmed by inspection (not by triggering each one) — a transaction reads before it writes:

- `FeedRepository.subscribeFeedWrite` (`FeedRepository.kt`, the `db.transaction {}` covering
  `getByUrl`/`nextSortOrderInGroup` then `upsert`) — the one this was observed against.
- `FeedRepository.moveFeedsOutOfFolderLocked` (reads `nextSortOrderInGroup` then writes per feed in
  the same transaction), reached via `FolderRepository.deleteFolder`'s transaction.

`FeedRepository.moveFeed` and `FolderRepository.reorderFolders` read their ordering *before*
opening `db.transaction {}`, so they don't have this shape. (`moveFeed` holds `feedPlacementMutex`
across that read and its transaction, so it cannot interleave with a subscribe placing a feed in the
same group, while keeping the read outside the transaction.) Neither does the feed-refresh apply
transaction (`FeedRepository.kt`, around `applyFetch`), which only writes.

### How the test was worked around

`FeedRepositoryTest.subscribeFeedSerializesSortOrderAllocationAcrossConcurrentCalls` now subscribes
a feed with no `<item>`s. With no articles to write and no search-index catch-up, the first
call's post-mutex work is read-only, so there is no longer a second writer left to race the upgrade.
See `testing.md`'s note on concurrent-write tests for the general pattern.

## A sync merge holds the database's write lock for its whole length

**Status**: not fixed — closing it needs a redesign of the merge outside the scope of the change that
found it (see "What a real fix would need").

### Symptom

While a sync merges the downloaded cloud file, every other write to the local database waits. A write
that waits longer than `busy_timeout` (`SQLITE_BUSY_TIMEOUT_MS`, 5 s) fails with `SQLITE_BUSY`: a read/star
change or a feed-list or settings write handed off by the UI is reported as a local-write failure in the
notification center (`LocalWriteFailureReporter`) and the UI undoes it, and a feed refresh's apply step
fails for that cycle.

### Diagnosis

`DatabaseMerger.merge` runs every `MergeSql` statement inside one transaction on its dedicated connection,
so the write lock is held from the first statement to the commit or rollback, and the time grows with the
number of rows merged, so with enough articles — which an unlimited cache retention allows — it exceeds
`busy_timeout`. A constraint failure whose verdict needs the deferred re-run (a FOREIGN KEY failure with
violations already in main, and on Android any constraint failure with violations already in main) holds
it longer still, since the remaining statements run a second time; other constraint failures do not re-run
(see "Merge Failure Classification" in [sync-architecture.md](sync-architecture.md)).

For scale only: a one-off measurement on one desktop machine, with 20,000 articles of about 2 KB each,
took about 350 ms for a successful merge and about 1.7x that for a failure that needed the re-run. No test
in the repository reproduces these figures, so they go stale as the merge changes; the claims above do not
depend on them.

### Ruled out

- **A longer `busy_timeout`** only moves the size at which writes start failing, and makes every write
  that waits on the merge wait longer.
- **WAL mode** lets readers run alongside the merge, but writers are still serialized behind it.
- **Splitting the merge into several transactions as it stands** would let a failure or a crash leave a
  half-applied merge behind while `cloud_file_rev` says nothing about it.

### What a real fix would need

A merge applied in bounded transactions that each leave the database consistent (foreign-key-safe order)
and can be re-applied idempotently, with `cloud_file_rev` recorded only after the last one, so the lock is
released between them and a merge interrupted at any point is simply completed by the next sync. It has to
keep every guarantee in "Merge (`DatabaseMerger` + `MergeSql`)" and "Merge Failure Classification" in
[sync-architecture.md](sync-architecture.md) — including the classification's in-transaction checks — and
the FTS rule that the merge is followed by `indexMissing()`.

## Google Drive: an upload can overwrite another device's upload unnoticed

**Status**: not fixed — external (Google Drive API) limitation: Drive offers no conditional content
update. The overwritten changes are not lost while the device that made them keeps syncing.

### Symptom

Two devices syncing through Google Drive at the same moment can both pass the upload's revision check;
the later write then replaces the earlier one in the cloud without either device seeing a conflict.
The earlier device's changes are missing from the cloud file — and from any device that downloads it —
until that device syncs again.

### Diagnosis

Every other provider makes the revision check part of the write itself (Dropbox's `rev`, the `If-Match`
of OneDrive and Nextcloud), so the server refuses a write based on a stale revision. Drive has no such
precondition: neither `files.update` nor its media upload documents an ETag / `If-Match` check or a
`412` response in the Drive API v3 reference. `GoogleDriveStorage.upload` therefore reads the current
`version` and compares it before writing, which leaves a window between the read and the write. To
re-check whether this still holds, look in the official `files.update` reference (and its media upload)
for an ETag / `If-Match` precondition or a documented `412`.

The first upload has the same shape. Drive has no atomic create-if-absent, so two devices creating the
file at once each create one, and `GoogleDriveStorage.resolveCreateRace` keeps the lowest id. Drive's
file listing is not guaranteed to show a just-created file yet, so a device can see only its own file,
believe it won, and keep writing there until the listing catches up — the re-check after
`RACE_RECHECK_DELAY_MS` narrows that window but cannot close it. Once the listing shows both,
`findFile` resolves every device to the lowest id. The revision a Drive device records holds the file's
id as well as its `version`, so a device moved off its own file sees a revision it has not merged: its
next sync downloads and merges the winner's file and uploads the result, so its content reaches the
cloud. Its own file is left behind unused.

### Why nothing is lost in the usual case

The overwritten device keeps its own changes in its local DB and recorded the revision its own write
produced. Its next sync finds a different revision in the cloud, downloads and merges that file, and
uploads the merged result — so its changes return to the cloud, under the same merge rules as any other
sync: a change that ties with the other device's on the same timestamp goes to the cloud side, exactly as it
would had the two uploads not raced. They are lost from the cloud only if that device never syncs again
(uninstalled, reset, or disconnected from Drive first).

### Ruled out

- **The sync retry loop** retries only a conflict it detected; an overwrite inside the window is not
  one.
- **Inferring the overwrite from the version the write returns** — `version` counts every server-side
  change to the file, metadata included, so a jump of more than one does not prove another upload.
- **A lock file** — creating it is itself an unconditional Drive write, with the same window.

### What a real fix would need

A server-side precondition on Drive's content update (an `If-Match` on `files.update`, or equivalent),
and an atomic create-if-absent (or a strongly consistent listing) for the first upload. With them,
`upload` would pass `expectedRev` to the write itself, as the OneDrive and Nextcloud storages do, and
`create` would fail outright instead of reconciling afterwards.

## CI-only: `UpdateDownloaderTest.progressArrivesWhileTheBodyIsStillStreaming` times out

**Status**: root cause not identified. The test was changed to report what the download actually did
instead of a bare timeout, so the next occurrence diagnoses itself — a diagnosability improvement,
**not** a fix for the underlying trigger.

### Symptom

The desktop test task fails with nothing but:

```text
UpdateDownloaderTest[desktop] > progressArrivesWhileTheBodyIsStillStreaming[desktop] FAILED
    kotlinx.coroutines.TimeoutCancellationException
```

It is intermittent: seen on more than one runner OS, never on more than one job of the same run,
and on commits that do not touch `UpdateDownloader` or its test.

### Why the failure carried no information

`UpdateDownloader.download` reports an ordinary failure as a `Result.Err` rather than throwing (see
[error-design.md](error-design.md)), and its `Result.Err` paths log nothing. The test waited only on
the progress `CompletableDeferred`, so a download that gave up before emitting any progress left
that deferred uncompleted forever — and the enclosing `finally` then cancelled the `Deferred`
holding the reason. `shared/build.gradle.kts` configures no `testLogging`, so even the one
`Log.warn` path would not appear in CI output.

### Ruled out

- **Not reproducible locally**: 40 runs on an idle machine plus 15 more with the CPU oversubscribed
  3× (macOS/arm64, JDK 26) — all green.
- **Not write backpressure stalling the test's feeder.** Ktor 3.5.2's `ByteChannel` suspends a writer
  only once its flush buffer passes `CHANNEL_MAX_SIZE` (1 MiB, `ktor-io`'s `ByteChannel.kt`); the
  test writes 320 KiB, so `writeFully` returns without waiting for the reader at all.
- **Not MockEngine buffering or duplicating the body.** `respond(ByteReadChannel, …)` passes the
  test's own channel straight through as the response body (`ktor-client-mock`'s `MockUtils.kt`) —
  no copy, no second reader.
- **Not `UpdateDownloader`'s `timeout {}` overrides.** MockEngine declares `HttpTimeoutCapability`
  and implements no timeout of its own, so `socketTimeoutMillis` never fires against it.

### What the next occurrence will show

`awaitFirstProgress` now races the progress reading against the download's own completion, so:

- a download that finishes first fails with its `Result` (including an `Err`'s `UpdateException`
  message),
- a download that throws propagates that exception as itself, and
- a genuine stall still times out, but reports whether the download coroutine is still running.

That separates the two remaining hypotheses — an early silent `Result.Err` versus the download
coroutine simply not being scheduled in time — which the bare timeout above cannot.

## Linux/GNOME: the tray menu briefly shows the previous label when it opens

**Status**: not fixed — external (GNOME Shell AppIndicator extension) design limitation, and not
fixable from Keryx's side at all (see "Why no emission timing helps" below). Cosmetic only: the
label settles on the correct value within a frame or two, and every click does the right thing.
Affects GNOME with the AppIndicator extension only, and only when the label changed while the menu
was closed. KDE Plasma, macOS, Windows and the Linux AWT tray fallback are all unaffected — none of
them read the label back out of a host-side cache.

### Symptom

With the window hidden to the tray on GNOME (AppIndicator extension), opening the tray menu shows
"非表示" (Hide) for an instant before it switches to "表示" (Show). The final label is correct; only
the first painted frame carries the previous one. The in-app update entry behaves the same way while
a download is running — its label and greyed-out state are a beat behind on that first frame.

This is the residue of a *different*, already-fixed bug, where the label stayed stale **forever** on
GNOME (see the `ItemsPropertiesUpdated` paragraph in `app-architecture.md`). Do not confuse the two.

### Diagnosis

Keryx's own side works correctly and was verified first: `SniDBusMenu.updateState` computes the
changed properties (`changedItemProperties` in `TrayMenuModel.kt`) and emits `ItemsPropertiesUpdated`
the moment the label changes, with no delay of its own. There is nothing to re-investigate in the app.

The delay is entirely in the extension's dbusmenu client (`dbusMenu.js` in
`ubuntu/gnome-shell-extension-appindicator`), in three steps:

1. An incoming property signal is gated on the client's own `active` flag, and simply parked while
   that flag is false:

   ```js
   if (signal === 'ItemsPropertiesUpdated') {
       if (!this._active && this.getRoot()?.hasChildren()) {
           this._flagItemsUpdateRequired = true;
           return;                                    // parked, not applied
       }
       this._onPropertiesUpdated(params.deep_unpack());
   }
   ```

2. `active` is assigned in exactly one place — `_onMenuOpenStateChanged(menu, state)` does
   `this._client.active = state` — so it is true only while the menu is open. Keryx's label changes
   always happen while it is closed (the window is hidden either from its own close button, or from
   this very menu item, which closes the menu as it fires), so in practice the signal is always parked.

3. Opening the menu runs `set active(true)`, which calls `_doPropertiesUpdate()` to re-read every
   known item through `GetGroupProperties`. That call is fire-and-forget — an `IdlePromise` plus a
   D-Bus round trip — and is **not** awaited before the menu is drawn.

So the menu is painted from the stale cache, then repainted an idle cycle plus one round trip later.

### Why no emission timing helps

The parking branch in step 1 is unconditional while the menu is closed, so emitting earlier, later,
or repeatedly all land in it. There is no dbusmenu call a server can make to push a property into a
client that has decided not to listen yet.

### Ruled out

- **Emitting `ItemsPropertiesUpdated` and `LayoutUpdated` together.** The `active` setter is
  `if (this._flagLayoutUpdateRequired) … else if (this._flagItemsUpdateRequired) …`, so the layout
  branch wins and the property refresh is postponed to the *next* open — strictly worse than the
  single flash we have now.
- **Making `AboutToShow` always report the layout stale.** The client answers a stale report with
  the same `_requestLayoutUpdate()`, whose `GetLayout` asks for `['type', 'children-display']` only
  and never `label`. It cannot refresh a label at all.
- **Any change to what `changedItemProperties` sends.** The client has to fetch *some* value when it
  first creates the item, and that value is whatever was current at the time; keeping properties out
  of the diff only makes its cache staler.

### What a real fix would need

Either the extension applying its parked updates before the first paint (awaiting
`_doPropertiesUpdate()` on the open path — an upstream change, not ours), or Keryx giving the toggle
item a label that does not depend on window visibility at all: one static "Show/Hide window" entry,
or two permanent "Show" / "Hide" entries.

The second was considered and **rejected**: it removes the menu's state indication on every platform
(or forces a platform-conditional menu shape) to erase a sub-frame artifact on one desktop
environment. The dynamic label is correct and instant on macOS, Windows, KDE Plasma and the AWT
fallback, and that is not worth trading away.

## A feed body's own JavaScript runs inside the article reader

**Status**: half fixed — the stylesheet half is closed (the reader's document declares a
`style-src 'unsafe-inline'` CSP, so an external stylesheet a feed body reaches for is never
fetched). The script half is deliberately left open: script-based SNS embeds need it, and
`ArticleWebView`'s link interception exists precisely to keep those embeds working.

### Symptom

On Android, opening `https://alfalfalfa.com/articles/11110022.html` rendered correctly for one to
two seconds and then restyled itself: the document padding disappeared, the title turned brownish
red and moved up, the font shrank, headings grew a thick brown left bar, and a "続きを読む" link
became a blue-violet button. Desktop did not reproduce it.

### Diagnosis

`presentation/article/ArticleWebViewHtml.kt` (`:shared`) embeds the feed body raw beneath a `<base href>` at the
article's own origin, and the reader's WebView has JavaScript enabled (`composewebview`'s
`WebSettings.isJavaScriptEnabled` defaults to `true`; the app overrides only
`desktopWebSettings.dataDirectory`).

That article's `content:encoded` carries a script that appends `/css/smartphone.css` to
`document.head` when the user agent matches `/iPhone|iPod|Android.*Mobile|Windows Phone/i`, plus a
`<link rel="stylesheet">` for the same file inside `<noscript>`, another for
`https://blogroll.livedoor.net/css/default2.css`, and four ad `<script>` tags. On Android the user
agent matches, `<base>` resolves the path to `https://alfalfalfa.com/css/smartphone.css`, and the
sheet applies once fetched — later in the cascade than the reader's own `<style>`, so it wins.
Every symptom maps to a rule in that sheet:

| Symptom | Rule |
| --- | --- |
| padding gone, lines tighten | `html, body, h1, … {margin:0; padding:0}` / `body {line-height:1}` |
| font shrinks | the same reset's `font-size:100%`, cancelling `html {font-size:N%}` |
| title turns brownish red | `a:link {color:#a52a2a}` — same specificity as `.article-title a {color:inherit}`, declared later |
| brown left bar on headings | `div.daily_popular_title {border-left:12px solid #8D4225; color:#532E0C}` |
| "続きを読む" button | `.article_bodyfooter .readmore a {background:#444499; color:#fff}` |

Desktop is spared only because its user agent fails that script's test, so `smartphone.css` is
never injected there. The code path itself is shared `commonMain`.

### What was fixed

`articleDocument` now emits `<meta http-equiv="Content-Security-Policy" content="style-src
'unsafe-inline'">`. With no URL source named for `style-src`, no external stylesheet is fetched —
statically linked, `@import`ed, or appended by script alike — while `'unsafe-inline'` keeps the
app's own `<style>` block and the body's `style=""` attributes working. The reader's chrome rules
additionally carry `!important`, which hardens the declarations they actually list against a
lower-specificity override — hardening, not isolation (see "Article Reader" in
[app-architecture.md](app-architecture.md)).

### What is deliberately left

No `script-src` is declared, so the body's scripts still run: ad and analytics code executes and
makes its own network requests, which does not sit comfortably with "no data sent to external
servers" in [external-spec.md](external-spec.md) §10, and a script can still inject an inline
`<style>` element (which `'unsafe-inline'` admits — the `!important` chrome rules absorb the
ordinary case, but only for the declarations they list: a property none of them marks
`!important`, or an equally-specific `!important` rule of the body's own, still restyles the
chrome, since the body follows the reader's `<style>` block). The trade is intentional: turning
JavaScript off would also take script-based SNS embeds with it.

The SwiftUI app's reader (`ReaderNavigationGate`) additionally refuses to become a browser: scripts
still run, but a navigation that would replace the whole page never loads in place — a click on it
opens in the external browser (http/https only), a top-level navigation nobody clicked (`location = …`,
a meta refresh) is dropped, and only an embed's own navigation inside an iframe loads. A click a script
synthesizes (`a.click()`, a cancelable dispatched `click`) is cancelled in the page itself. It is not
airtight, in two ways. `preventDefault` does nothing on a non-cancelable event, so a script
dispatching `new MouseEvent('click', {bubbles: true})` at a link still activates it. And the guard
finds the link through the event's `composedPath()` on `window`, treating any element with an `href`
or `xlink:href` attribute as one (so SVG and MathML anchors count, not only HTML's `<a>` and
`<area>`), so an anchor inside a *closed* shadow root (which `composedPath()` omits) or a
non-composed click dispatched inside a shadow root (which never reaches `window`) can also activate
the link. WebKit reports each of these as a user click — the page opens in the external browser
(http/https only) without anyone clicking. The Compose
readers (desktop, Android) keep their own link interception and do not have this rule. Apple's age
rating counts in-app browsing of arbitrary pages as "unrestricted web access" (16+), which this keeps
the SwiftUI app clear of.

A script's top-level navigation can also cost the *next* article its load. WebKit stops a document
load still in flight as soon as another top-level navigation starts, even one the gate then refuses,
and often reports nothing about the dropped load — so an article whose script keeps setting
`location` (measured: one doing so every millisecond lost the next article's load in 12 of 150 quick
switches) left itself on screen while the list showed the newly selected article. The SwiftUI
reader therefore tracks its own load until it commits and loads the document again when a
top-level navigation is refused or its load fails while it is still in flight, up to
`READER_DOCUMENT_RELOAD_LIMIT` times per document (`:shared`'s
`presentation/article/ReaderDocumentLoad.kt`; 0 of 150 after the change). The Compose readers, which
let such a navigation through rather than refusing it, lost loads the same way (4 of 150 on desktop)
and follow the same rule, checking a tag the document carries — see "A pushed document is pushed as
soon as the view is ready" in [app-architecture.md](app-architecture.md).

### What a real fix would need

Treating the body as untrusted markup rather than only containing its effects — stripping
`<script>`, `<link>`, `<style>`, `<base>`, `<meta>`, `<noscript>` and `on*` attributes with ksoup
and setting `isJavaScriptEnabled = false` (SNS embeds then degrade to the `<blockquote>` fallback
their own embed code ships), or isolating the body in an `<iframe sandbox srcdoc>` so it cannot
reach the reader's document at all.

For the shadow-DOM gap in the SwiftUI reader specifically, `WKNavigationAction` has no public way to
tell a user's click from a script's, so the fix is to record genuine user activation instead of
inferring it from the event path: the in-page script reports each trusted `click`/`pointerup`/key
event to the native side (a `WKScriptMessageHandler`), and `ReaderNavigationGate` opens a
`.mainFrameClick` externally only while such a report is fresh (about a second old) and unused. The
cost is that actions with no DOM click — iOS's long-press "Open Link", macOS's context-menu "Open
Link" — must be checked on a device so they are not cancelled too.

## Linux on arm64: the article reader has no native web-view binary, and the window freezes

**Status**: worked around, not fixed — the reader falls back to a Compose-drawn simplified view on
any platform/architecture pair the web-view library ships no binary for
(`platform/NativeWebViewSupport.kt` + `ui/article/ArticleContentView.kt`). The real fix is a
backend that has an arm64 Linux binary, which is a much larger change (see below). **Linux arm64 is
now a supported, released target** (`build.md`'s "Release (CD)" lists it alongside x86_64, and CI
publishes `-linux-arm64.{zip,deb,rpm,snap}` artifacts) — so this workaround is no longer only about
a development machine; every Linux arm64 user gets the simplified reader in the shipped build, not
just whoever happens to build from source.

### Symptom

On ARM64 Ubuntu (a VMware Fusion guest on an M2 Mac), `./gradlew :composeApp:run` opened the window
and then froze it completely: nothing painted inside, nothing responded. A `.deb` built and
installed on the same machine appeared to do nothing at all when launched.

### Diagnosis

Both are the same failure. `keryx.0.log` records it:

```text
java.lang.UnsatisfiedLinkError: Unable to load library 'composewebview_wry':
Native library (linux-aarch64/libcomposewebview_wry.so) not found in resource path
    at io.github.kdroidfilter.webview.wry.WryWebViewPanel.<clinit>
    at works.merc.keryx.app.ui.home.ArticleDetailPaneKt.ArticleWebView
```

`wrywebview-1.0.0-beta-02.jar` bundles exactly four native builds — `darwin-aarch64`,
`darwin-x86-64`, `linux-x86-64`, `win32-x86-64`. There is no `linux-aarch64`. macOS gets an arm64
build; Linux does not.

The freeze itself is what that error does on its way out. `UnsatisfiedLinkError` is an `Error`, not
an `Exception`, and it is raised from inside composition, so Compose's default window exception
handler catches it and opens a **modal** error dialog. A thread dump taken while "frozen" shows the
event-dispatch thread parked inside that dialog's own nested event loop, at ~0% CPU:

```text
"AWT-EventQueue-0" ... java.lang.Thread.State: WAITING (parking)
    at java.awt.Dialog.show(Dialog.java:1051)
    at androidx.compose.ui.window.WindowExceptionHandlerFactory_desktopKt.showErrorDialog
```

The packaged `.deb` hits the identical error; it simply has no console to show it on.

### Ruled out

Recorded so they are not retried:

- **Graphics / Skiko / OpenGL.** The obvious guess on a VM with no GPU passthrough, and wrong. The
  guest's `glxinfo -B` reports `llvmpipe` with `direct rendering: Yes` and GL 4.5, and the thread
  dump shows Skiko already running happily through `LinuxSoftwareRedrawer` /
  `AbstractDirectSoftwareRedrawer`. `-Dskiko.renderApi=SOFTWARE` changes nothing because software
  rendering is already in use.
- **A missing jlink module in the packaged runtime.** There is precedent for that class of bug
  (`jdk.security.auth`, `jdk.localedata` — see `composeApp/build.gradle.kts`'s `modules(...)`), so
  it was the first suspicion for the `.deb`. The log shows the same `UnsatisfiedLinkError` under
  `/opt/keryx/bin/Keryx`, so it is not that.
- **The single-instance lock.** A frozen `./gradlew run` left holding the lock would make a second
  launch forward its activation and exit silently, which looks exactly like "nothing happens". The
  log line for that path (`Single-instance lock held by another instance`) does not appear; the
  packaged run logs `Acquired single-instance lock` and then fails on the same error.
- **AWT/X11 being broken in the guest.** A plain Swing `JFrame` with a button renders and responds
  normally there.

### The workaround

`isNativeWebViewSupported()` probes the library's own UniFFI entry point once, before the reader
would have loaded it anyway, and `ArticleWebView` renders the article with Compose when the probe
fails. That fallback (`ui/article/ArticleContentView.kt` + `ArticleBlockViews.kt`) reproduces block
structure (including `<figure>`/`<figcaption>` and `<dl>`/`<dt>`/`<dd>`), inline decorations
(including a feed's own `style=""` color/size/weight — `ui/article/InlineCss.kt`), and images
(`srcset`/lazy-load attributes, and an image-only paragraph or link promoted to a real block image);
embedded content that genuinely needs a browser engine (iframes, script-driven widgets, video)
becomes a button that opens it externally. Its type scale and block margins are plain functions of
the document's own UA-default sizing (`ArticleTextStyles.kt`), not this app's `MaterialTheme
.typography` — see "Article Reader (native WebView)" in `app-architecture.md` for the reasoning.

`-Dkeryx.reader.webview=false` forces the fallback on any desktop machine — the practical way to
work on its appearance on machines where the native web view is available. Linux arm64 uses the
fallback by default because no native web view is available for that target. `-Dkeryx.reader.webview=true` forces the web view back on, which matters if a library
upgrade ever renames the probed class and turns the probe into a permanent false negative (that
case logs a distinct warning) — but it does so by skipping the probe entirely rather than re-running
it, so it is only safe where a native binary actually exists; passed on Linux arm64, it reaches the
same native initialization and reproduces the `UnsatisfiedLinkError`/frozen-looking modal dialog
documented above. Both properties are read only by the desktop (JVM) `actual`
(`NativeWebViewSupport.desktop.kt`), relayed there from `./gradlew :composeApp:run`'s own JVM by a
`tasks.withType<JavaExec>().configureEach` block in `composeApp/build.gradle.kts` (a `JavaExec` task
does not otherwise inherit the launching JVM's system properties, so this relay is what makes
`-Dkeryx.reader.webview` reach the running app rather than being silently swallowed by Gradle
itself). Android's `actual` always reports the web view as available — `android.webkit.WebView`
comes from the OS itself — so neither property has any effect there.

### What a real fix would need

The upstream library has moved to `dev.nucleusframework:composewebview`, and its current release
**does** bundle `nucleus/native/linux-aarch64/libcompose_webview_linux.so`. Adopting it is not a
version bump: the Wry desktop backend is gone, and its README states that the desktop web view now
requires the Nucleus Tao backend — *"Desktop is Tao-only … Swing/Compose Desktop without Tao will
not host the WebView"*, with the app's entry point becoming
`nucleusApplication(backend = NucleusBackend.Tao)`. That replaces Compose Desktop's own
`application`/`Window`, which `main.kt` builds the SNI tray, the AWT menu bar, `WindowChrome`,
`FilePicker` and the macOS `Desktop` handlers on top of. Worth doing only as its own project —
Linux arm64 being a released target now (see "Status" above) makes the simplified reader's UX gap
more visible, but doesn't by itself justify replacing the app's entry point.

## CI-only: macOS `packageDmg` fails with `hdiutil detach` "Resource busy"

**Status**: external (macOS runner) flakiness, mitigated with a retry rather than fixed. jpackage's
own DMG step is outside this app's control.

### Symptom

`./gradlew packageDmg` fails on a `macos-latest` GitHub Actions runner with:

```text
Execution failed for task ':composeApp:packageDmg' (registered by plugin 'org.jetbrains.compose').
> Command failed (exit 16): hdiutil detach /dev/disk31s1
```

It is intermittent: when first seen, the runs just before it had passed on the same code, with
only a docs-only commit in between.

### Ruled out

- **Not caused by a code change.** The branch it was first seen on changed nothing that feeds the
  macOS packaging (no Compose plugin version, packaging configuration or macOS CI step).
- **Not concurrent `packageDmg` runs on the same runner.** GitHub Actions runners are single-use
  VMs, so even runs that `ci.yml`'s `concurrency:` setting lets overlap (any two v* runs, or runs
  on different branches) never share a disk, and a stale mount can't come from a second run.

jpackage builds a DMG by mounting a scratch disk image, copying the app bundle in, and detaching it
again; the orphan-process cleanup in the same log (`Terminate orphan process: pid (diskimages-help)`)
shows a disk-image helper process still alive at job teardown, consistent with the detach racing
against something (Spotlight/`mdworker`, most likely) still holding the volume open.

### Mitigation

`.github/workflows/ci.yml`'s "Verify packaging (macOS)" step retries `packageDmg` up to 3 times, but
only when the failure log actually contains `hdiutil` — any other failure (a real packaging
regression) still fails on the first attempt. Before retrying, any volume still mounted under
`/Volumes/Keryx*` is force-detached so the next attempt doesn't land on a differently-named mount
point.

### What a real fix would need

There is no app-side lever here — the mount/detach is entirely inside jpackage's native DMG
assembly, and the race is a property of the CI runner's environment (Spotlight indexing a freshly
mounted volume), not of anything this app configures. A retry is the only available mitigation
short of GitHub or the JDK addressing it upstream.

## iOS: going back to the article list crashed in `UICollectionView`

**Status**: no longer reachable by design, cause not confirmed. Since the article list became a
`UICollectionView`, no pending scroll is left for UIKit to validate (see "What changed" below); the crash
was never reproduced, so whether that is what stops it is unknown. The entry stays until that is known.

### Symptom

v0.27.0 on an iPhone SE (3rd generation), iOS 26.5, aborted on an `NSInternalInconsistencyException` from
`-[UICollectionView _validateScrollingTargetIndexPath:raisingExceptionIfNecessary:]`, 32 seconds after the
process launched. SwiftUI's `UpdateCoalescingCollectionView.updateContent()` asked for the scroll, in
`layoutSubviews`, during the layout that starts the navigation pop from the reader. No Keryx frame is on the
stack but `main`, and the report does not hold the exception's message, so the index path and row count are
unknown. The Kotlin GC threads were idle.

The reader's swipe pager is a `UIPageViewController`, not a collection view, so the `List` the article list
then was is the only SwiftUI collection view in this layout.

### What was ruled out

Measured on the iOS 26.3 simulator, with the article list still a SwiftUI `List`. The first four rows made the pop from code, the last two with an edge swipe
driven by a UI test:

| Scenario | Result |
| --- | --- |
| `scrollTo` asked while the list is under the reader | Happens (selection moves, restored selection) |
| Pending scroll to row 51, rows shrink from 60 to 9, go back | No crash |
| Reader restored at launch, go back | No crash |
| Reader restored at launch, rows shrink from 59 to 34, go back | No crash |
| Scroll held, edge swipe back to the list | No crash |
| Scroll held, edge swipe cancelled | No crash |

So a pending scroll made stale by rows shrinking is not enough on its own.

### What changed

The article list is now a `UICollectionView` list, as the sidebar is (`Home/ArticleCollectionView.swift`;
see "Article list (iOS)" in [app-architecture.md](app-architecture.md)). `ScrollViewProxy.scrollTo` is no
longer used: the controller carries a scroll out at once with `scrollToItem`, against the rows its data source
holds at that moment, and only once the list is on screen; until then only the latest scroll waits, and one
whose row is gone by then is dropped. Nothing is left pending inside UIKit for a later layout to validate.

### Re-checking

If a crash with the same stack is reported from a build with this change, the cause is elsewhere: reopen this
entry with the new report.

## macOS: dropping a folder between folders crashed inside SwiftUI

**Status**: worked around, not fixed — external (SwiftUI) defect. Every feed and folder drop is
decided and applied without SwiftUI's drop handling (`Home/Sidebar/SidebarDropGuard.swift`).

### Symptom

Without the guard, dropping a folder between folders in the sidebar traps on macOS 26
(`EXC_BREAKPOINT`, a Swift assertion; observed with v0.27.0 on macOS 26.7.1):
`-[NSOutlineView performDragOperation:]` → `OutlineListCoordinator.outlineView(_:acceptDrop:item:childIndex:)` →
`outlineView(_:performDropInsertion:with:at:)` → `ListOutlineItem.subscript` → `HeterogeneousViewIDs.subscript` →
`HeterogeneousCollection.element(at:)`. No Keryx frame is on the stack. It happens on every such drop,
including with all folders collapsed.

### Cause

A bug in SwiftUI's `List` on macOS, reproduced in a standalone app with the same structure: a
`ForEach` of folder rows with `.onInsert`, inside a `List` in sidebar style. Dropping a folder between
folders crashed whenever the folder rows had any one of a `DisclosureGroup`, a `.badge`, a context menu
or an `.onDrop`; with rows carrying only `.itemProvider` and `.tag` (like the unfoldered feed rows) it did
not. Feeds inserted between other feeds — in another folder or the same one — never crashed.

### What was ruled out

| Change | Result |
| --- | --- |
| Folders `Section` without `isExpanded`, or with a `header:` view | Crashes |
| No `.onInsert` on the feeds inside each folder | Crashes |
| No `.tag`, no `.badge`, or no `.onDrop`/context menu on the folder row | Crashes |
| Folder rows without `DisclosureGroup` (but with the row's other modifiers) | Crashes |
| `.onMove` instead of `.onInsert` on the folders | No crash, but a feed can no longer be inserted between another folder's feeds |
| Wrapping SwiftUI's outline data source in a forwarding proxy | Breaks SwiftUI's own drop handling (no insertion line, no drop onto a folder row) |

Every row element the sidebar needs is enough to trigger it, so no change to the view structure avoids it.
A Swift assertion is a trap, not an exception, so it cannot be caught.

### The workaround

Beyond the crash, SwiftUI's drop handling refuses some positions (a folder over a feed row inside a
folder, the middle of a folder row), accepts positions that do not exist (children of a feed row,
which crash too), moves the drop target while refusing it, and answers "copy", which puts "+" on the
cursor. So `SidebarDropGuard` keeps SwiftUI out of feed and folder drops altogether, and the `List`
carries no `.onInsert`/`.onDrop`:

- It adds methods to the class of the `NSOutlineView` behind the `List` and to the class of its data
  source (SwiftUI's `OutlineListCoordinator`) — `class_addMethod` where the class only inherits the
  method, `method_setImplementation` (keeping the old implementation) where it has its own. Each
  method acts only for an outline that has the guard's handler attached and a drag carrying a feed
  or folder type; everything else goes on to the original implementation (looked up from the
  superclass on every call where none was replaced, as `super` would).
- The data source's `outlineView(_:validateDrop:proposedItem:proposedChildIndex:)` decides the
  destination and sets it with `setDropItem`; the outline's `performDragOperation:` applies it, if
  the destination still resolves to the same action. SwiftUI's `validateDrop`/`acceptDrop` are
  never called for these drags.
- The outline's `expandItem:` refuses to spring anything open during a folder drag.
  SwiftUI's outline overrides `expandItem:` to record the row as expanded before expanding it, so
  refusing there — not in `expandItem:expandChildren:`, which it calls next — keeps that record
  unchanged; SwiftUI applies its own expansion state another way, which is left alone.
- The guard registers the two drag types itself (`registerForDraggedTypes`), since no SwiftUI drop
  modifier does any more.
- A feed's copy under a tag gets an id of its own; with the feed's id, the outline mistook the copy
  for the original.

The rules for where an item lands are in "Sidebar (macOS)" in [app-architecture.md](app-architecture.md).

The class, not the instance, is changed because something else may already have swizzled the
instance: in the test host, KVO had replaced the outline's class before the guard's first chance to
install, and subclassing KVO's class would break KVO's undoing it. Swift's `type(of:)` skips KVO's
class, so the methods land on SwiftUI's own classes either way.

How it turns out when something does not go as intended:

| Case | Result |
| --- | --- |
| No outline is found next to the guard | The guard is not installed: sidebar drag and drop does nothing (no crash), and moving and tagging remain available from the context menu. No fault is logged: the outline is looked for again on every update, and missing it for a moment right after launch is normal. The integration test catches this case |
| The outline is found, but it has no data source or its classes lack a method the guard overrides | The guard is not installed, with the same result. A fault is logged once (`SidebarDropGuard` category) |
| A proposed row carries no readable marker (`SidebarRowMarker`), or its position disagrees with the sidebar's own order | The guard is installed, but each affected drop is refused and nothing moves. A row that should be marked but is not logs a fault once (`SidebarDropGuard` category) |
| KVO observes the outline, before or after the guard is installed | No effect: the methods are on SwiftUI's classes, which KVO's class inherits from. Covered by a test |
| Another `List` in the app shares the classes | Its outline has no handler attached, so every method goes on to the original implementation |
| SwiftUI's own implementation of a replaced method changes later | Other drags get the kept original, or the superclass's method looked up per call |

`KeryxTests/SidebarDropGuardTests.swift` hosts a `List` shaped like the sidebar in an offscreen
window and checks what the guard relies on: that the `List` is an `NSOutlineView` whose data source
answers `validateDrop`, that each row's marker is read from its row, that each row is read as the
row it is — also with unmarked sections and rows before and between the sidebar's own — that a drop
onto an unmarked row is refused, and that drops sent through a stand-in `NSDraggingInfo` are decided and applied by the guard. CI
runs it on macOS 26 (`apple-release-toolchain`) and macOS 15 (`apple-oldest-supported`), so a new SDK or macOS
that changes these fails there. It cannot cover macOS 14, which users can still run, or a real mouse drag; re-check those by hand on each
new macOS: drop a folder at the top, the middle and the bottom of a folder row, over a feed inside a
folder, at the top and at the end, and drop a feed onto a folder, "No folder", a tag and a feed's
copy under a tag.

Before macOS 26 the sidebar's search field (`.searchable(placement: .sidebar)`) is a root item of
the outline too, ahead of the sections, and not a group item as every section is. The guard
therefore never tells a row by its position among the roots or within a section; it only checks that
a folder or tag row read by its marker sits at its own index among its siblings. Every row the guard
needs carries an invisible marker view behind its content (`SidebarRowMarker`), naming its
`SidebarItemID`, which the guard finds in the outline's existing row view (`makeIfNecessary: false`
— a freshly made row gets its content only on a later turn). The Folders section is told by a marked
child, the Tags section is reached as the parent of a marked tag row, the "No folder" section by its
header's marker, and a feed's folder by its parent's marker. An accessibility identifier cannot
serve: SwiftUI exposes it neither on the row views nor on the outline's accessibility rows. The test
sidebar carries the same search field, so this structure is covered wherever the tests run on macOS
15, as CI's `apple-oldest-supported` does; on 14, re-check it with the manual drops listed above.

### What a real fix would need

A fix in SwiftUI. Even then, the guard stays as long as SwiftUI's drop handling misplaces or refuses
drops as described above. Driving the outline directly instead (an `NSOutlineView` wrapped in
`NSViewRepresentable`, as the iOS sidebar does with a `UICollectionView`) would remove the reliance on
SwiftUI's internals, at the cost of drawing the rows, selection and metrics the `List` now provides.

## iOS: a row jumps when a swipe starts

**Status**: worked around, not fixed — external (UIKit/SwiftUI) defect. A row with a context menu
and swipe actions (both part of the spec, `external-spec.md` §9) jumps when a swipe starts. Keryx
avoids it on the article list and the sidebar by pointing each menu's press highlight at a clear view
(see "Workaround" below); the defect itself is still there.

### Symptom

Swiping an article row on iOS, the row did not follow the finger at first. Its leading edge was cut
away (the unread dot shaved off from the left) while the rest stayed still, and a few frames later
the whole row jumped to catch up with the finger and followed it normally from there. Swiping left, it
read as "the left side disappears, then the row slides". The swipe still worked; only its first
frames were wrong. Sidebar rows (feeds, folders, tags) did the same.

Measured with a slow (60 pt/s) XCUITest drag, recorded with `simctl io recordVideo` and read frame by
frame at 60 fps: the row stayed put for about 200 ms, then moved about 30 pt in one frame on the
iOS 17.0 simulator (iPhone 15 Pro), and about 17 pt on the iOS 26.5 simulator (iPhone 17 Pro); a
sidebar row moved 27 pt and 17–18 pt. It was smaller on iOS 26, which is likely why it was first
noticed on iOS 17.

### Diagnosis

Temporary logging of the cell's view tree showed what UIKit does as the swipe starts: it puts a mask
on the cell and moves the cell, the mask counter-moving so that it stays at the screen's leading edge.
The positions read in the app moved smoothly, about one point per frame, from the first frame. The
recording did not: the mask was drawn at its new place while the cell's content was still drawn where
it started, so the strip between them was cut away, until the content caught up in one frame.

**The cause is the context menu's press highlight.** While the finger is down, the menu highlights
the row (the view its highlight preview targets), and that highlight keeps the row drawn in place
while the swipe moves the cell under its mask. A touch probe showed that it depends only on when the
swipe is recognized: a swipe recognized more than about 375 ms after the finger landed jumps, and an
earlier one does not. How long the row had been left alone beforehand does not matter; swipes made in
quick succession only looked fine because the finger moved sooner.

Each element was taken away in turn, four swipes each on iOS 17.0, measuring the largest move in one
frame at the start of the drag:

| Variant | Largest move in one frame |
| --- | --- |
| The app before the workaround | 31–35 pt |
| Without the row's context menu | 2–3 pt |
| The row as a tap gesture instead of a `Button`, context menu kept | 30–35 pt |
| The menu on the `List` (`contextMenu(forSelectionType:)`) with a selection, so it opens | 15–31 pt |

A minimal SwiftUI app with none of Keryx's code (a plain `List` of rows with `.swipeActions` on
both edges and a one-item `.contextMenu`) does the same: with the menu, 20–31 pt on iOS 17.0
(three swipes in four) and 15–16 pt on iOS 26.5 (all four); without it, 2.3 pt at most on both. The
sidebar, a plain `UICollectionView` list with UIKit's own context menu, did it too.

### Ruled out

- **This app's own code.** The minimal app above has the jump, and taking out the measuring code
  changed nothing.
- **The row's `Button`.** Replacing it with a tap gesture kept the jump.
- **Rebuilding the list or a scroll made just before** (the `List`'s rebuild on window attachment and
  on return from the sidebar, and its held scrolls, since removed with the `List`), and the return
  flash. The jump happened just as often long after any of them.
- **Moving the menu from the row to the `List`.** The jump went away only while the menu could not
  open (the list had no selection); once it could, the jump came back.
- **SwiftUI redrawing the row during the swipe.** No row was redrawn during any swipe measured.
- **Other ways of attaching the menu**, tried in the minimal app with four swipes each. In the same
  runs, the plain menu measured 30 pt on iOS 17.0 and 11–15 pt on iOS 26.5.

  | Variant | iOS 17.0 | iOS 26.5 |
  | --- | --- | --- |
  | `.contextMenu` before `.swipeActions` instead of after | 7–30 pt | 6–15 pt |
  | A `.contextMenu(menuItems:preview:)` preview | 7–30 pt | 9–15 pt |
  | `.contentShape(.contextMenuPreview, …)` | 30–31 pt | 10–16 pt |
  | A UIKit `UIContextMenuInteraction` on the cell, highlighting the cell (UIKit's default) | 30 pt | 2–15 pt |

- **Moving to a `UICollectionView`.** The sidebar already is one, using UIKit's own context menu, and
  it jumped just the same.

### Workaround

Each row's menu points its press highlight, and its dismissal, at a clear view the size of the cell
(`ContextMenuPressHighlight`). The clear view leaves nothing behind when the cell moves, and the menu's
own preview shows the row instead. Since nothing visible is highlighted, the row itself is hidden
while the menu is up and comes back as the menu closes. Otherwise it would show beside the preview
where the background is only dimmed, not blurred (iPad).

- **Article list** (`ArticleCollectionViewController`): the collection view's own menu, with the
  highlight and dismissal previews returned from its delegate, and the row's look (`ArticleRowLabel`)
  hosted as the preview. SwiftUI's `.contextMenu` cannot be told where to point its highlight; while
  the list was a SwiftUI `List`, the workaround had to find the `List`'s own cell by walking up the view
  tree. macOS keeps SwiftUI's `.contextMenu`.
- **Sidebar** (`SidebarCollectionViewController`): the same, with an image of the cell as the preview.

The one visible difference is the press itself: the row no longer swells slightly while the finger is
down, before the menu opens.

With the workaround, a slow swipe moved at most 3 pt in one frame on every swipe measured:

| Row | iOS 17.0 | iOS 26.5 |
| --- | --- | --- |
| Article list | 2.0–3.0 pt | 1.3–2.3 pt |
| Sidebar | 2.3–2.7 pt | 1.3–1.7 pt |

### What a real fix would need

A fix in UIKit, so that a swipe that starts on a row with a context menu moves the row from its first
frame, whatever the menu highlights. The workaround could then go.

### Re-checking after an iOS or Xcode update

On each new iOS release, check on a simulator whether the workaround is still needed:

1. Take out the highlight and dismissal previews: remove `highlightPreviewForItemAt` /
   `dismissalPreviewForItemAt` from both collection views (`ArticleCollectionView`,
   `SidebarCollectionView`).
2. Swipe a row slowly, starting the move about half a second after touching it. Or record it with
   `xcrun simctl io <device> recordVideo` and step through the frames.
3. If the row follows the finger from the start, with no part of its leading edge cut away first, the
   defect is fixed for that version.

Once it is fixed on every supported iOS version, remove the workaround and delete this entry.
