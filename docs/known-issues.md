# Known Issues

[日本語](known-issues.ja.md)

Defects that are understood but deliberately not fixed, with the evidence behind that decision.
Each entry records what was ruled out, so a later investigation doesn't repeat the same work.

## Linux: OPML import/export crashed the JVM with SIGSEGV in libawt_xawt.so

**Status**: Resolved — by replacing the Linux OPML file dialog backend with `javax.swing.JFileChooser`
(`platform/FilePicker.desktop.kt`), leaving `java.awt.FileDialog` in place on macOS/Windows. Kept in
full because the evidence is decompiled OpenJDK internals that would otherwise have to be
re-derived, and because it documents why a plain JVM-flag workaround was rejected.

### Symptom

On Linux, opening the OPML import or export dialog (Settings ▸ Data Management ▸ Import/Export OPML)
froze the window and then crashed the whole process with `SIGSEGV`:

```text
# A fatal error has been detected by the Java Runtime Environment:
#  SIGSEGV (0xb) at pc=0x00007f0318391a5f, ...
# Problematic frame:
# C  [libawt_xawt.so+0x51a5f]
```

Import and export crashed at two different offsets in the same library
(`+0x51a5f` for import, `+0x51af9` for export). macOS was unaffected.

### Diagnosis

`platform/FilePicker.desktop.kt` used `java.awt.FileDialog`. On Linux, `sun.awt.X11.XToolkit
.createFileDialog()` selects `GtkFileDialogPeer` — GTK3-backed native code inside `libawt_xawt.so` —
unless `sun.awt.disableGtkFileDialogs=true` is set. macOS uses `LWCToolkit`/`NSSavePanel` instead, so
it never touches this code path at all, matching the reporter's "fine on macOS" observation.

Decompiling `sun_awt_X11_GtkFileDialogPeer.c` from the OpenJDK source and disassembling the two crash
addresses in each `hs_err_pid*.log` pinned both crashes to the same root cause — a NULL
`JNU_GetEnv(jvm, JNI_VERSION_1_2)` result, dereferenced with no NULL check:

| Operation | pc | Function | What the disassembly showed |
| --- | --- | --- | --- |
| Import | `+0x51a5f` | `filenameFilterCallback` (only registered when a `FilenameFilter` is set — import sets one, export does not) | `mov rsi,[r13+8]` = `filter_info->filename`, then a call through the `NewStringUTF` vtable slot |
| Export | `+0x51af9` | `handle_response` | `cmp r12d,-3` (`GTK_RESPONSE_ACCEPT`), then a call through the `ExceptionCheck` vtable slot |

`JNU_GetEnv` returns NULL only when the calling thread is not attached to the JVM, and both
`hs_err` headers confirm exactly that: `Current thread is native thread`. The process already had
`libgtk-3`, `libgdk-3`, and **`libwebkit2gtk-4.1`** mapped — the article reader's native WebView
(`io.github.kdroidfilter.webview`/wry) is mounted unconditionally for the lifetime of the pane (see
the WebView entry below), so it is a second GTK consumer sharing the process's default
`GMainContext`. That is the plausible reason a plain Swing app without an embedded WebView would
not hit this: with only one GTK consumer, GTK's own signal dispatch stays on a thread the JVM
already knows about.

### Ruled out

- **A bug specific to import or to export** — disproved: both crash inside the same native file, at
  the two call sites of the exact same unchecked API (`JNU_GetEnv`), one on each dialog's response
  path.
- **`FilenameFilter` itself being the trigger** — the export crash (`handle_response`) has no
  `FilenameFilter` involved at all; the shared cause is the JNI attachment, not the filter.

### Workarounds that did not work / were rejected

- **`-Dsun.awt.disableGtkFileDialogs=true`** — would route Linux through `XFileDialogPeer` instead,
  avoiding the GTK peer (and the crash) entirely. Rejected: it must be set before AWT/toolkit
  initialization (an ordering constraint shared with `ui/theme/DesktopLookAndFeel.installLookAndFeel`
  — see `app-architecture.md`), and `XFileDialogPeer` is an even older, Motif-era XAWT dialog than the
  GTK2-era Swing Look & Feel this app already replaced with FlatLaf for exactly that dated-appearance
  reason (see "Desktop Tray" and "Native file dialogs" below). It also does not pick up FlatLaf at all,
  unlike a `JFileChooser`.

### How this was resolved

The Linux backend was replaced outright with `javax.swing.JFileChooser`
(`SwingFilePickerBackend` in `platform/FilePicker.desktop.kt`), mirroring the same
Linux-Swing-vs-AWT split already used for context menus (`NativeMenu.desktop.kt`'s
`defaultPopupHandle`). `JFileChooser` is pure Swing on every Look & Feel — including the
FlatLaf-failed system-L&F fallback, since `GTKLookAndFeel`'s own `GTKFileChooserUI` is itself pure
Swing — so it never reaches the native GTK code that crashed. See "Native file dialogs (platform
branch)" in `app-architecture.md` for the resulting design, including the overwrite-confirmation
detail below and the XDG Desktop Portal noted there as future work.

One behavioral detail worth recording: decompiling the same native file showed that the pre-crash
Linux `FileDialog` already had native overwrite confirmation
(`gtk_file_chooser_set_do_overwrite_confirmation(dialog, TRUE)`, set unconditionally for the SAVE
action) — matching what macOS/Windows still provide natively. `JFileChooser` has no such prompt of
its own, so the fix restores it explicitly (`resolveSavePath` + a `JOptionPane` confirmation) rather
than silently regressing Linux relative to its own prior behavior. The same source showed no
extension-auto-append logic anywhere, on any platform, so that was deliberately not added.

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

Listed so they are not tried again as a *Wayland* fix — attempt 3 is what shipped before this fix,
kept because it was a genuine, working fix for X11:

- Mirroring AWT's computed drop action into `setCursor()` (attempt 1).
- Forcing `DragSource.DefaultMoveDrop` unconditionally from `DragSourceListener` callbacks alone
  (attempt 2).
- Adding `DragSourceMotionListener.dragMouseMoved` as an additional, more frequently firing trigger
  for the same `setCursor()` call (attempt 3, previously shipped).

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

## Selecting an article after none was selected flickered the whole window

**Status**: Resolved — by keeping the article reader's native WebView permanently mounted instead
of composing it only while an article is selected.

### Symptom

With no article selected (the detail pane showing "Select an article to read it"), clicking an
article briefly flickered the *entire* window — the feed list and article list panes too, not just
the detail pane. Reproduced in both light and dark theme. Switching from one already-selected
article to another never flickered.

### Diagnosis

The article reader (`ui/home/ArticleDetailPane.kt`) renders article HTML through
`io.github.kdroidfilter.webview`'s `WebView` composable, which (confirmed by decompiling
`webview-compose-jvm.jar` and `ui-desktop-1.11.1.jar`) is a heavyweight AWT `SwingPanel` hosting
`WryWebViewPanel extends javax.swing.JPanel` — a real native OS browser surface (a skiko
`HardwareLayer` `java.awt.Canvas`), not a Compose-drawn texture.

`androidx.compose.ui.viewinterop.SwingInteropContainer.executeScheduledUpdates()` ends with:

```text
root.validate()
root.repaint()
```

where `root` is the *window's* interop container, not just this pane's. Both adding/removing this
heavyweight component (`SwingInteropContainer.place()`/`unplace()`) and moving it
(`SwingInteropViewHolder.layoutAccordingTo()` → `setBounds(...)`) schedule this call, so either one
repaints the whole window for a frame.

The pane's previous structure had an early return for the "no article selected" state:

```kotlin
if (current == null) {
    Box(...) { Text("Select an article to read it") }
    return
}
```

so the native WebView was never composed at all until an article was selected — going from no
selection to a selection therefore added the heavyweight panel to the window **for the first
time**, triggering the whole-window repaint above. Switching between two already-selected articles
never hit this path, since the WebView stayed mounted continuously — matching the exact symptom
reported (no flicker article-to-article, only none-to-article). The same early return also existed
for an article with neither `content` nor `summary` (the "no content" branch), so switching to/from
such an article had the identical flicker.

### Ruled out

- **Compose recomposition / layout thrash** — `HomeScreen` deliberately does not collect
  `selectedArticle`, so selecting an article does not recompose the feed list or article list panes
  at that level. The flicker is not a Compose-side relayout.
- **Pane resizing** — the feed list and article list pane widths come from persisted settings and
  are untouched by article selection.

### How this was resolved

The WebView is now composed unconditionally for the lifetime of the pane
(`ArticleDetailPaneContent` in `ui/home/ArticleDetailPane.kt`), never behind an `if`. Since nothing
Compose-drawn can appear over a heavyweight AWT surface in the same window (the same limitation
that makes this app's dialogs real `DialogWindow`s — see `ui/common/KeryxDialogs.kt`), the "no
article selected" placeholder and the "no content" notice are now rendered as HTML *inside* the
same WebView (`ui/article/ArticleWebViewHtml.kt`'s `articlePlaceholderHtml`/`articleNoContentHtml`,
sharing one `<style>` block with `wrapArticleHtml` so neither ever flashes a default white page in
dark mode). The toolbar above the reader (star/mark-unread/copy-URL/open-in-browser) is also always
present now, with buttons disabled rather than hidden when nothing is selected — this keeps the
toolbar's Compose structure identical across states, which is what keeps the WebView's bounds from
moving (a bounds change alone, via `layoutAccordingTo`, can also trigger the same whole-window
repaint).

### Workarounds that did not work / were not attempted

Recorded so they are not retried:

- **Keep the WebView mounted but zero-sized or off-screen when nothing is selected** — does not
  work: a bounds change routes through `SwingInteropViewHolder.layoutAccordingTo()`, which schedules
  the same whole-window `validate()`/`repaint()` as adding/removing the panel.
- **Draw a Compose scrim/placeholder over the mounted WebView** — impossible: a heavyweight AWT
  surface always composites above lightweight Compose content in the same window.
- **Patch `SwingInteropContainer.executeScheduledUpdates()`** — not reachable from application code
  (it lives inside `ui-desktop-1.11.1.jar`).
- **Share a `Modifier.height(...)` constant between this toolbar and the article list's header
  row to "guarantee" a stable height** — considered and rejected: the article list's header
  includes a `ToggleChip` with real text, whose height can grow past the icon-only baseline at a
  high font-scale setting (this app supports 0.8×–1.6×), so forcing a shared fixed height there
  would clip it. The detail toolbar needs no such constant — all of its children are fixed-size
  icons unaffected by font scale, so keeping its exact Compose structure identical between states
  is enough on its own.

## Dialogs occasionally opened at an unexpected size

**Status**: Resolved — by replacing the dialog auto-fit's bounded, one-shot correction with a
lifetime-long drift guard (`DesktopModalWindow` in `ui/common/KeryxDialogs.desktop.kt`, decided by
`nextDialogFit` in `ui/common/WindowGeometry.desktop.kt`). Kept because two earlier attempts at this
same bug are still visible in that file's comments, and because the evidence below is decompiled
Compose Desktop internals that would otherwise have to be re-derived.

### Symptom

Opening the Settings dialog or the About dialog *occasionally* produced a window far too small to
show its content — stuck at the placeholder's 240dp height, at the ~80x28 macOS gives a
not-yet-sized dialog, or narrow enough that the settings tab bar clipped its trailing tabs.
Intermittent, and permanent for that dialog's lifetime once it happened (the dialogs are
`resizable = false`, so it could not be dragged back to a usable size). Reported on macOS; the
mechanism is platform-independent. Closing and reopening the dialog usually produced a correct one,
because each open creates a brand-new `DialogWindow` and re-runs the race.

### Diagnosis

Every dialog is sized by measuring its Compose content and writing the fitted size into
`DialogState.size`. Decompiling `ui-desktop-1.11.1.jar` shows what Compose does with that:

```kotlin
// androidx.compose.ui.awt.SwingDialog's update lambda, run via UpdateEffect
// (a SnapshotStateObserver feeding a Channel — i.e. asynchronous)
if (state.size != appliedState.size) { window.setSizeSafely(state.size, Floating); appliedState.size = state.size }

// androidx.compose.ui.awt.SwingDialog's ComponentAdapter
override fun componentResized(e) {
    currentState.size = DpSize(dialog.width.dp, dialog.height.dp)  // mirrored back into DialogState
    appliedState.size = currentState.size                          // ...and marked "already applied"
}
```

`DialogStateImpl.size` is a `mutableStateOf` with the default (structural) equality policy, and
`AwtWindow` calls `window.isVisible = true` — peer realization, where the ~80x28 comes from — from a
launched coroutine, after the composition pass that already ran the first update. Three consequences:

1. **Re-asserting the same `DpSize` does nothing, twice over.** The `mutableStateOf` write does not
   invalidate, and even if the update lambda did re-run, `state.size == appliedState.size` skips the
   native call. The previous "re-assert across frames" loop was therefore only a poll.
2. **The loop stopped watching after one matching frame**, and nothing re-armed it: the measured
   content size is deliberately independent of the window size (the `requiredWidthIn` /
   `requiredHeightIn` overrides on the measured `Box`, themselves the fix for an earlier
   stuck-narrow-forever bug), so `capturedContentPx` never changes again and the `snapshotFlow`
   never re-emits.
3. **Compose cannot self-heal either**, because `componentResized` writes the native size into
   *both* `DialogState.size` and its own applied copy — a size that landed behind Compose's back is
   self-consistent from Compose's point of view.

So any size application that landed *after* the loop exited was permanent, and whether it landed
before or after was pure scheduling — hence the intermittency, and hence the stuck values matching
the placeholder and the un-sized peer exactly.

A second, independent defect had the same visible symptom: the fit read `LocalDensity` from the
*caller's* (main window's) composition while converting pixels measured in the *dialog's*
composition. On screens with different scale factors that is off by their ratio — at owner density 2
and dialog density 1 a 640dp-wide content becomes a 320pt window, which both clips the tab bar and
over-wraps the content until its height hits the screen cap.

### Ruled out

- **Re-asserting the fitted size more times, or for more frames** — a same-value write is a no-op at
  two independent layers (see 1 above), so the loop could never actually re-apply anything; it only
  ever waited for Compose's own asynchronous application to land.
- **Keeping the bounded loop but breaking later / never** — `withFrameNanos` is driven by the
  dialog scene's `BroadcastFrameClock`, which only delivers frames while the scene renders. A
  Settings dialog occluded or minimized to the tray mid-loop would stall it indefinitely.
- **Re-triggering off the content measurement** — impossible by construction: `requiredWidthIn` /
  `requiredHeightIn` make the measurement a function of content only. Removing them would reintroduce
  the stuck-narrow-forever bug they were added to fix.

### How this was resolved

`DialogState.size` is now part of the observed value (`snapshotFlow { capturedContentPx to
dialogState.size }`), which turns Compose's own mirror-back of every native resize into a drift
event — for the dialog's whole lifetime, with no polling. Each event recomputes the target from the
measured content and, if the window does not match, writes `DialogState.size` (the path that packs a
not-yet-displayable window) *and* pushes the size straight onto the AWT window (which closes the
same-value no-op hole); `componentResized` then re-syncs Compose's state and re-arms the guard. The
collector body does not suspend, so it neither depends on the frame clock nor can be re-entered.
`nextDialogFit` caps corrections per target so a window manager that refuses the geometry cannot
spin the guard, logging once via `Log.warn` when it gives up rather than failing silently, and only
re-places the dialog on a target change so a drift correction never yanks a window the user dragged.
The density is now read inside the dialog's own composition.

The direct push must carry the **size and the position together, as one `setBounds`**
(`applyWindowGeometry`). A first cut pushed only the size to AWT and left the position to
`DialogState`, which meant the size landed synchronously and the position a `Channel` hop later
through `UpdateEffect` — and a frame painted in that gap showed the dialog at its final size but at
the location AWT gives a freshly constructed `Window`: the screen origin plus the screen insets,
i.e. the top-left corner, from which it then jumped to the centre. (`java.awt.Window.init` offsets
the initial location that way; Compose's own `WindowLocationTracker.getCascadeLocationFor` uses the
same base point.) As with the size, whether a frame lands in the gap is pure scheduling, so this too
was intermittent.

### Residual limitation

A native resize that never fires `COMPONENT_RESIZED` is invisible to the guard. Nothing short of
permanent polling could see it, and the Skia scene's own size derives from the same peer event, so
an extra `onSizeChanged` would buy nothing. Accepted.

## Linux: modeless dialogs (Settings, About) shrank their window width to near-zero

**Status**: Resolved — `fitWindowSize` (`ui/common/WindowGeometry.desktop.kt`) no longer derives
the requested window *width* from measured content at all; it now always uses the dialog's own
fixed width, using the measurement only for height. That closes the feedback path described below
regardless of what triggers a transient narrow report from the window manager.

### Symptom

On Linux (KDE Plasma, reproduced under Wayland/XWayland) opening the Settings dialog or the About
dialog showed the correct width at first, then over roughly 1–2 seconds the window's outer frame —
not just its drawn content — narrowed progressively until it was almost zero pixels wide. The
dialogs are `resizable = false`, so the user could not drag them back open; closing and reopening
reproduced it again. Never observed on macOS. Every other dialog built on the same
`KeryxAlertDialog`/`DesktopModalWindow` machinery — add feed, add/edit/delete folder or tag, rename
feed, every confirmation dialog — was unaffected.

### Diagnosis

A real-machine log line was the first hard evidence that this was the app requesting a collapsing
size, not merely a window manager refusing a larger one:

```text
WARNING [KeryxDialogs] Dialog stayed at 11.0.dp x 535.0.dp after 5 attempts to fit 1.0.dp x 535.0.dp
```

Height matched between target and actual (535dp both — the height auto-fit was working correctly);
only width had diverged, and critically the *target* itself — not just what had been successfully
applied — had degraded to 1.0dp.

Comparing which dialogs reproduce (Settings, About) against which don't (every other
`KeryxAlertDialog` caller) matched exactly one code difference: `modal = false` — `AboutDialog`
passes it explicitly, and `KeryxTabDialog` (Settings' host) always does — versus the default
`modal = true` every other caller left unset. Inside `DesktopModalWindow` the only parameter this
flag changes that actually reaches AWT is `modalityType` (`DocumentModal` vs `Modeless`);
`resizable` and the effective decoration are identical either way, and both branches funnel into
the same `SwingDialog`/`ComposeDialog` implementation (confirmed by reading the Compose Desktop
1.11.1 sources jar).

The mechanism itself was a self-amplifying measurement loop:

1. `fitWindowSize` computed the requested width purely from the measured content `Box`'s width
   (`contentPx.width.toDp()`). That `Box` was bounded by `requiredWidthIn(max = initialWidth)`,
   which pins a *maximum* but has no *minimum* — the incoming constraint it clamps to is whatever
   the window's current client area happens to be at measure time.
2. On Linux, a modeless dialog's client area was reported momentarily narrower than requested while
   the window manager was placing it (apparently not something that happens for a document-modal
   one — the two only differ in that one AWT modality flag). Under that momentarily narrower
   incoming max, the enforce-incoming `Column(Modifier.width(initialWidth))` inside the `Box`
   measured narrower than `initialWidth` too.
3. That narrower measurement fed straight back into the next requested window width via
   `componentResized` → `DialogState.size` → the drift guard's `snapshotFlow` collector, which
   requested the narrower width from AWT.
4. The narrower window produced an even narrower client-area report on the next tick, and the loop
   repeated — narrower window → narrower measurement → narrower request — compounding down to ~1dp.

`nextDialogFit`'s five-attempt-per-target correction cap could not stop this: the *target* itself
kept changing on almost every tick (each new, narrower width was a different `DpSize`), and the
budget reset unconditionally on every target change, so the cap effectively never engaged until the
loop bottomed out on its own at AWT's practical size floor.

### Ruled out

- **The window manager forcibly shrinking the outer frame from outside** — disproved by the log:
  the *target* the app was requesting, not merely the size actually applied, had itself degraded to
  1dp. This was the app asking for a tiny width, not a window manager refusing a larger one.
- **Something specific to the Settings dialog** (e.g. its tab content changing height as
  ViewModel-driven state loads, repeatedly moving the fit target) — disproved by `AboutDialog`
  reproducing identically despite completely static content. The two share nothing in their content
  or lifecycle; the only thing they share is being routed through `DesktopModalWindow`'s
  `modal = false` branch.

### How this was resolved

- `fitWindowSize` no longer takes measured width as input at all. It now takes the dialog's own
  fixed `contentWidth` (the caller's `initialWidth`) directly, and uses the measured `contentPx`
  only for height — the one axis genuinely meant to auto-fit. This closes the feedback loop
  outright: whatever momentarily narrow client area a window manager reports during placement can
  no longer become the next requested width, on Linux or anywhere else, `resizable = true` dialog or
  not.
- Reusing a height-only decoration allowance for a size comparison that is fundamentally about both
  axes was itself a latent, independent asymmetry (the *outer* window width was being compared
  against a *content-only* target width, with no compensation on that axis at all — the reason
  the settings tab bar could clip a few trailing pixels even without this bug). `decorationAllowanceFor`
  now computes a `DpSize` allowance from the window's real `insets` on both axes, falling back to
  the previous height-only fixed guess only while insets still read all-zero (before the window
  manager has reparented/decorated the window — a known AWT/X11 timing quirk).
- `nextDialogFit`'s correction budget now only resets on a target change when the *previous* target
  was actually reached (`DialogFitState.targetReached`), rather than on every target change
  unconditionally. This closes the specific hole that let a moving target dodge the cap
  indefinitely — a defense that holds even if some future change reintroduces a target that can
  drift on its own, independent of the width fix above.
- `applyWindowGeometry` gained an optional `minSize` floor, and `DesktopModalWindow` now also sets
  `window.minimumSize` at creation. Both are a last-resort safety net, not the primary fix — given
  `resizable = false`, any future collapse would otherwise be unrecoverable by the user.
