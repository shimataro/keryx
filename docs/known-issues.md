# Known Issues

[日本語](known-issues.ja.md)

Defects that are understood but deliberately not fixed, with the evidence behind that decision.
Each entry records what was ruled out, so a later investigation doesn't repeat the same work.

## Linux Wayland/XWayland: drag cursor stuck on "no-drop" despite a successful drop

**Status**: Resolved — by removing OS-level drag-and-drop from the feed list entirely. The feed/folder
reorder gesture is now a hand-rolled Compose-native drag (`ui/home/FeedListDragController.kt` +
`FeedListDragGestures.kt`: manual `pointerInput` tracking, a Compose-drawn floating ghost, direct
hit-testing), so no XDnD/XWayland cursor negotiation happens at all on any session type. The
investigation below is kept in full, since it remains valuable context against ever reintroducing
`Modifier.dragAndDropSource`/`dragAndDropTarget` (real OS-level DnD) for this or a future
intra-window-only drag — the analysis of *why* XWayland can't be worked around from the client side
still holds if that's ever reconsidered.

### Symptom

Dragging a feed or folder row in the feed list shows the OS's forbidden ("no-drop") cursor for the
whole gesture instead of a drag ghost. The drop completes successfully regardless — no data or
functional impact, but a working feature looks broken.

### Diagnosis

Three code-level attempts were tried, in order, against real Linux hardware:

1. Mirror AWT's own computed drop action into `DragSourceContext.setCursor()` from
   `DragSourceListener`'s `dragEnter`/`dragOver`/`dropActionChanged`.
2. Force the cursor unconditionally to `DragSource.DefaultMoveDrop` from those same callbacks,
   regardless of the computed drop action.
3. Add a `DragSourceMotionListener` (`dragMouseMoved`, which fires on every pointer move regardless
   of drop-target acknowledgment) as an additional trigger for the same unconditional `setCursor()`
   call, on the theory that the status-based callbacks might never fire for an intra-window drag.

All three had **zero observable effect** on the reporter's machine. Rather than guess a fourth
variant, temporary diagnostic logging was added to record exactly which callbacks fire during a
drag. Two tests with that logging, same build, only the session type changed:

Plasma **Wayland** session — forbidden cursor shown throughout:

```text
Linux drag-cursor fix installed
Observed dragMouseMoved for the first time this drag (dropAction=0)
Observed dragEnter for the first time this drag (dropAction=2)
Observed dragOver for the first time this drag (dropAction=2)
Drag ended; callbacks observed this gesture: [dragMouseMoved, dragEnter, dragOver]
```

Plasma **X11** session — cursor correct throughout, forbidden icon never appears:

```text
Linux drag-cursor fix installed
Observed dragMouseMoved for the first time this drag (dropAction=0)
Observed dragEnter for the first time this drag (dropAction=2)
Observed dragOver for the first time this drag (dropAction=2)
Observed dragExit for the first time this drag
Drag ended; callbacks observed this gesture: [dragMouseMoved, dragEnter, dragOver, dragExit]
```

The two sequences are functionally identical (AWT resolves the drop action to `ACTION_MOVE` (`2`)
within milliseconds on *both* sessions, and the same `setCursor(DragSource.DefaultMoveDrop)` calls
are reached on *both*), yet only X11 shows the corrected cursor. The only variable that changed
between the two tests was the session type.

### Cause

Keryx's Linux build runs on AWT's X11 toolkit — there is no general-availability native-Wayland
AWT/Compose Desktop toolkit — so under a Wayland session the app runs as an **XWayland** client.
XWayland bridges the client's X11 XDnD protocol to the compositor's native `wl_data_device` Wayland
protocol, and the drag cursor shown during that bridged operation is compositor-drawn from the
negotiated Wayland DnD action, not from the X11 cursor the client requests via
`XDefineCursor`/`DragSourceContext.setCursor()`. This is a known category of XWayland DnD limitation,
not anything specific to Keryx or Compose Desktop: the exact same calls that fix the cursor outright
on X11 are silently ignored once bridged through XWayland, because the compositor — not the
client — owns the cursor for a Wayland-native DnD grab.

### Ruled out

- **AWT computing the wrong (rejected) drop action** — disproved: it resolves to `ACTION_MOVE`
  within milliseconds on both sessions, in both logs above.
- **The `DragSourceListener`/`DragSourceMotionListener` callbacks never firing at all** — disproved:
  the identical sequence of callbacks fires on both X11 and Wayland.
- **Something specific to Keryx's or Compose Desktop's code** — the same build, same call sequence,
  produces different visible results purely based on session type, with no code change between the
  two tests.

### Workarounds that did not work (on Wayland; all three work on X11)

Listed so they are not tried again as a *Wayland* fix — attempt 3 is what's currently shipped, kept
because it is a genuine, working fix for X11:

- Mirroring AWT's computed drop action into `setCursor()` (attempt 1).
- Forcing `DragSource.DefaultMoveDrop` unconditionally from `DragSourceListener` callbacks alone
  (attempt 2).
- Adding `DragSourceMotionListener.dragMouseMoved` as an additional, more frequently firing trigger
  for the same `setCursor()` call (attempt 3, shipped).

### What a real Wayland fix would need

Full control over the drag cursor under a Wayland session would require driving the drag through
Wayland's native `wl_data_device`/`wl_data_source` protocol directly — setting the cursor via a
genuine Wayland surface, not an X11 `Cursor` — which means either a native-Wayland AWT/Compose
Desktop toolkit (not available in the JDK/Compose Multiplatform versions this project targets), or
hand-rolling a JNI bridge to libwayland bypassing AWT entirely for this one interaction. Both are far
out of proportion to a cosmetic cursor icon, given the drop itself already succeeds on every session
type.

### How this was actually resolved

Rather than pursue either option above, the feed list's drag was rebuilt to not use OS-level DnD at
all: Keryx's drags are always intra-window (reordering within the same feed list — never to another
app/window), so `java.awt.dnd` was never actually required. `LinuxDragCursorFix.kt` (attempt 3 above)
and the AWT-backed `platform/FeedDragAndDrop.kt`/`FeedDragAndDrop.desktop.kt` and
`ui/home/DragAndDropSourceWithThreshold.kt` were deleted outright, replaced by a hand-rolled drag
hosted on the feed pane's single non-virtualized container (auto-scroll can otherwise dispose a
per-row gesture mid-drag) with its own Compose-drawn ghost overlay. Bonus: Linux gets a real drag
ghost for the first time (X11 AWT never supported one either, even before the cursor bug), and the
gesture is unit/UI-testable for the first time (`ui/home/FeedListDragTest.kt`) — the OS-level version
needed real, unconstructable AWT events and was documented as untestable in `docs/testing.md`.

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
   `LaunchedEffect(selected?.id, …)` → `scrollToIndexIfNeeded` (`ui/home/HomeCommon.kt`). Only a
   change of the *selected article's id* re-keys it.
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
  "mark all read". None of these change the selected article's *id*, so the effect never re-keys.

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
  alias jars, so there is no duplicate-class conflict. The `compose-material3` pin is also not a
  factor — that version *is* the newest stable (1.10 and 1.11 only ever shipped alphas).

### Workarounds that did not work

Listed so they are not tried again:

- Replacing the animated scroll (`animateScrollToItem` / `animateScrollBy`) with the instant
  `scrollToItem` / `scrollBy`.
- Waiting for `isScrollInProgress` to clear before scrolling into view.
- Both of the above combined.
- `requestScrollToItem`, which defers the scroll to the next measure pass.
- Upgrading to Compose Multiplatform **1.12.0-beta03** (confirmed to actually resolve in the
  dependency graph; still reproduces 5 / 5).

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
