# Known Issues

[日本語](known-issues.ja.md)

Defects that are understood but deliberately not fixed, with the evidence behind that decision.
Each entry records what was ruled out, so a later investigation doesn't repeat the same work.

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

**Status**: not fixed — the affected call sites are narrow and unobserved in production; a real fix
touches the core data-access layer. Worked around in the one test it made flaky.

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
inside `subscribePlacementMutex.withLock { db.transaction { ... } }`, but the *next* thing that
call does after releasing the mutex — `articleRepository.upsertParsed(feedId, fetched.articles)`, a
separate per-feed `db.transaction {}` — can still be mid-flight, still holding a lock, when the
second call's own transaction tries to upgrade its SHARED read lock to RESERVED for its write.

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
- `FeedRepository.moveFeedsOutOfFolder` (reads `nextSortOrderInGroup` then writes per feed in the
  same transaction), reachable directly and via `FolderRepository.deleteFolder`'s transaction.

`FeedRepository.moveFeed` and `FolderRepository.reorderFolders` read their ordering *before*
opening `db.transaction {}`, so they don't have this shape. Neither does the feed-refresh apply
transaction (`FeedRepository.kt`, around `applyFetch`), which only writes.

### How the test was worked around

`FeedRepositoryTest.subscribeFeedSerializesSortOrderAllocationAcrossConcurrentCalls` now subscribes
a feed with no `<item>`s. With nothing for `articleRepository.upsertParsed` to insert, the first
call's post-mutex work is read-only, so there is no longer a second writer left to race the upgrade.
See `testing.md`'s note on concurrent-write tests for the general pattern.

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

## iOS: the article list's pull-to-refresh spinner is missing after returning from the sidebar

**Status**: worked around, not fixed — external (SwiftUI) defect. At a phone width,
`HomeView` rebuilds the article list each time the collapsed split view brings it forward from the
sidebar (`ArticleListView.remountGeneration`), and the list rebuilds itself once more when it is first
put into a window (`ArticleListView.attachedToWindow`). The cost is that a list brought back from the
sidebar starts at the top rather than where it was scrolled to — as NetNewsWire's article list does
on the same route.

### Symptom

On an iPhone, pull the article list to refresh, wait for it to finish, go back to the feed list, and
pick the same item again. Pulling the list now refreshes and syncs as
usual, but no spinner appears; the list is simply pulled down and springs back.

A related symptom has the same origin: the first pull after launch left the list resting tens of
points short of the top (the top of the list still partly under the navigation bar), about three
times in five.

Both were diagnosed on the iOS 27 simulator with Xcode 27; that is where they were last seen, not a
range they are known to be limited to. See "Re-checking after an iOS or Xcode update" below. They
were diagnosed with an earlier layout in which the article list had a heading row of its own; the
list now uses the standard inline navigation title and has no heading, and has not been re-measured
since.

### Diagnosis

Temporary logging walked every window's view hierarchy 300 ms into each pull. SwiftUI hosts a
`.refreshable` list's control as a `PlatformRefreshControlProxy` in a container just under the
navigation bar, outside the list's own `UICollectionView`. On the first pull it is there, spinning.
After the column has been popped and pushed again, no refresh control exists anywhere in the
hierarchy, yet `HomeObservable.pullToRefresh()` still runs. SwiftUI keeps the same list (the same
selection means no identity change) and never re-attaches the control it dropped.

The list a phone-width split view pushes for the first time also has its control under the navigation
bar, and that is where the related symptom comes from: when the control finishes, the top content
inset shrinks from 176 to 116 but the offset settles at about −58 to −76 instead of −116. A list built
once its column is in the navigation stack gets the control inside its own `UICollectionView`, and
there the offset follows the inset. Both kinds of list are the navigation bar's tracked content
scroll view (`contentScrollView(for: .top)`), so the bar's own scroll-edge behavior is unaffected.
The inset and offset figures above were measured with the earlier layout that had a heading row,
not with the current inline title.

### Ruled out

- **A view drawn over the control** (the layering bug fixed for the sidebar's `UIRefreshControl` in
  `SidebarCollectionView.swift`). The control is absent, not hidden.
- **A stale control state** (alpha, hidden, frame). The same reason.
- **A regression in this app.** The missing spinner predates the app's own article-list heading and
  pull-to-refresh handling.
- **Re-attaching only the action.** `EnvironmentValues.refresh` is read-only, so the action cannot be
  removed and restored without changing the list's identity.
- **Returning from the reader.** The control survives that route, so the list is not rebuilt there and
  keeps its exact position and the return flash.
- **The pinned header or `.contentMargins(.top, 0)`** of the earlier layout, for the first-pull
  offset. Without the margin it still occurred, and with the header as an ordinary first row the list
  still rested short of the top. This was measured with that earlier layout; the current one has
  neither, and has not been re-measured.
- **Rebuilding as the first push starts** (when `compactColumn` changes). The column is not in a
  window yet, and the rebuilt list still gets its control under the navigation bar.
- **Rebuilding a fixed 500 ms after the first push.** It works, but resets a list the reader has
  already started scrolling.
- **Scrolling a rebuilt list back to the row that was topmost.** A `scrollTo` right after the rebuild,
  with the topmost row tracked through `onGeometryChange`, made a touched row jump for a moment when
  the list was then scrolled. Starting at the top avoids it.

### The workaround

`HomeView` bumps `articleListRemountGeneration` when `compactColumn` goes from `.sidebar` to
`.content` at a compact width (the condition is `ArticleListRemount`, covered by `KeryxTests`);
`ArticleListView` applies it as part of the content's `.id`, so the rebuilt list gets a fresh
control. The other part is `attachedToWindow`, set once when `WindowAttachmentProbe` (outside the
rebuilt content) first reports being put into a window — as the first push starts showing the
column, before the list can be touched.

### What a real fix would need

A SwiftUI fix that re-attaches the control when the column comes back, and settles the offset with
the inset when a control under the navigation bar finishes. Once those ship for every supported iOS
version, drop `remountGeneration`, `attachedToWindow`, `WindowAttachmentProbe` and
`ArticleListRemount`, so the list keeps its scroll position again.

### Re-checking after an iOS or Xcode update

Run this after raising the minimum iOS version or moving to a new Xcode, rather than trusting the
version above indefinitely. Remove `ListIdentity` from the `.id` in `ArticleListView.body` (leaving
the probe and the generation in place), build, and on an iPhone simulator check both symptoms:

1. Pull the article list, go back to the feed list, pick the same item, and pull again: does the
   spinner appear?
2. Launch, pull the article list once, and let the refresh finish, five times over: does the list
   always come back to rest at the top, its first row fully below the navigation bar?

If both hold on every supported iOS version, remove the workaround as above and delete this entry.
If either fails, restore the `.id` and update the version this entry was last seen on.

## iOS: going back to the article list crashed in `UICollectionView`

**Status**: mitigated, cause not confirmed. Scrolls no longer reach a list that is not on screen
(`Home/ArticleListScrollGate.swift`), but the crash was never reproduced, so whether that stops it is
unknown.

### Symptom

v0.27.0 on an iPhone SE (3rd generation), iOS 26.5, aborted on an `NSInternalInconsistencyException` from
`-[UICollectionView _validateScrollingTargetIndexPath:raisingExceptionIfNecessary:]`, 32 seconds after the
process launched. SwiftUI's `UpdateCoalescingCollectionView.updateContent()` asked for the scroll, in
`layoutSubviews`, during the layout that starts the navigation pop from the reader. No Keryx frame is on the
stack but `main`, and the report does not hold the exception's message, so the index path and row count are
unknown. The Kotlin GC threads were idle.

The reader's swipe pager is a `UIPageViewController`, not a collection view, so the `List` of the article list
is the only SwiftUI collection view in this layout.

### What was ruled out

Measured on the iOS 26.3 simulator. The first four rows made the pop from code, the last two with an edge swipe
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

### Not yet tried

iOS 26.5, a device, and rows growing while the list is hidden.

### Remaining risk

On a list that is on screen, a `scrollTo` and a row change can still land in the same update: there is no
callback for when a `scrollTo` has been carried out, so the two cannot be strictly kept apart.

The gate also opens as the pop starts, not when it ends. On the simulator, a held scroll was carried out about
half a second before the pop finished, and in a cancelled edge swipe it was carried out and the list then left
the screen again. So a held scroll can still reach the list during the transition the crash happened in. Waiting
for the transition to finish would close this, but a row swiped off screen would then jump after the pop, with
its return flash mostly over, so it is left to the real fix below.

### What a real fix would need

Moving the article list from a SwiftUI `List` to a `UICollectionView`, as the sidebar already is:
`scrollToItem` then runs synchronously against rows the app knows, with nothing left pending. It has to carry
over the rows, swipe actions and pull-to-refresh, so it is a task of its own.

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
| The sections are not laid out as expected | The guard is installed, but each affected drop is refused and nothing moves. No fault is logged |
| KVO observes the outline, before or after the guard is installed | No effect: the methods are on SwiftUI's classes, which KVO's class inherits from. Covered by a test |
| Another `List` in the app shares the classes | Its outline has no handler attached, so every method goes on to the original implementation |
| SwiftUI's own implementation of a replaced method changes later | Other drags get the kept original, or the superclass's method looked up per call |

`KeryxTests/SidebarDropGuardTests.swift` hosts a `List` shaped like the sidebar in an offscreen
window and checks what the guard relies on: that the `List` is an `NSOutlineView` whose data source
answers `validateDrop`, that its root items are the `List`'s sections in order (the headerless
All/Starred section first, then Folders, "No folder" and Tags), that each row is read as the row it
is, and that drops sent through a stand-in `NSDraggingInfo` are decided and applied by the guard. CI
runs it on `macos-latest`, so a new SDK or macOS that changes these fails there. It cannot cover the
older macOS releases users run (14 and later) or a real mouse drag; re-check those by hand on each
new macOS: drop a folder at the top, the middle and the bottom of a folder row, over a feed inside a
folder, at the top and at the end, and drop a feed onto a folder, "No folder", a tag and a feed's
copy under a tag.

### What a real fix would need

A fix in SwiftUI. Even then, the guard stays as long as SwiftUI's drop handling misplaces or refuses
drops as described above. Driving the outline directly instead (an `NSOutlineView` wrapped in
`NSViewRepresentable`, as the iOS sidebar does with a `UICollectionView`) would remove the reliance on
SwiftUI's internals, at the cost of drawing the rows, selection and metrics the `List` now provides.
