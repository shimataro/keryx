# Known Issues

[日本語](known-issues.ja.md)

Defects that are understood but deliberately not fixed, with the evidence behind that decision.
Each entry records what was ruled out, so a later investigation doesn't repeat the same work.

## macOS: clicking a notification banner does not restore a tray-hidden window

**Status**: not fixed — external (JDK/AWT) limitation, and the app-side `MacTray` code that
attempted to work around it has been **removed as dead code** (it never actually did anything —
see "The custom wiring was dead code" below). Restoring via the tray icon click or by relaunching
the app (single-instance forwarding, e.g. clicking the Dock icon while already running) both work
correctly, so the window is never permanently unreachable; only the notification banner's own
click-through is affected, and only while tray-hidden.

### Symptom

With the window hidden to the tray on macOS, clicking a new-article desktop notification does
nothing at all — no window flash, no Dock icon reappearing, no sound, nothing. Clicking the
banner's "表示" (Show) action button dismisses the banner but likewise has no other effect. The
same click, when the window is merely backgrounded (visible but unfocused/behind other windows),
brings the window to front correctly.

### Diagnosis

An earlier version of `MacTray.kt` wired notification clicks through AWT's
`TrayIcon.addActionListener(...)` — the only API `TrayIcon` exposes for a click on a
`displayMessage(...)`-shown banner. Temporary diagnostic logging (`Log.info` as the very first
statement inside that `ActionListener`, and at each subsequent stage of `main.kt`'s
`activationRequests` collector) confirmed via `tail -f` on a real packaged build that **the
`ActionListener` is never invoked at all** when the click happens while the window is tray-hidden
(Accessory activation policy) — zero log lines, at any level, anywhere in the chain. The same
collector, reached instead via the ordinary tray-icon click or via a second app launch being
forwarded to the running instance (both logged, both restore correctly), proved the restore logic
itself (`main.kt`'s `activationRequests` collector, including the Accessory→Regular
activation-policy reordering fix living there) was not the problem.

This points at `TrayIcon`'s native macOS peer (`CTrayIcon`), which bridges `displayMessage(...)`
through the deprecated `NSUserNotification` API. The click-through delegate callback
(`userNotificationCenter:didActivateNotification:`) apparently is not reliably bridged back into
Java's `ActionListener` when the owning app has no Dock icon (`NSApplicationActivationPolicyAccessory`)
— i.e. exactly the tray-hidden state this feature existed for. This could not be narrowed further
from Kotlin/Java code alone; it would need decompiling `CTrayIcon`'s native implementation (as was
done for the Linux `GtkFileDialogPeer` crash elsewhere in this file) or reproducing it in a minimal
pure-AWT test app outside this codebase, neither of which has been done.

### The custom wiring was dead code

A follow-up check settled the question of whether the `ActionListener`-based code contributed
*anything*: the exact same behavior — front-on-click while backgrounded, no restore while
tray-hidden — was confirmed on the `v0` branch, i.e. **before** `MacTray` had any
`onNotificationClicked`/`ActionListener` wiring at all. This proves the "backgrounded → comes to
front" case was never the app's own code running; it is macOS's own default notification
click-to-activate behavior for any Regular-policy app, which happens regardless of whether the app
registers a notification delegate. The custom AWT wiring added across a handful of commits (see
git history around `MacTray.kt`) never ran on the one path it was built for (tray-hidden) and was
redundant on the one path where the `ActionListener` does fire — so it has been removed outright:
`MacTray` no longer takes an `onNotificationClicked` parameter or registers a `TrayIcon`
`ActionListener` at all. Linux SNI's own, independently-working notification-click handling
(`LinuxTray`, via `LinuxNotifier`'s D-Bus `ActionInvoked` signal) is untouched — `KeryxTray`'s
`onNotificationClicked` parameter and `main.kt`'s `activationRequests.tryEmit(Unit)` callback still
exist and still feed it.

### Ruled out

- **A bug in `main.kt`'s restore/activation-policy logic** — disproved: the exact same
  `activationRequests` collector, reached via the tray-icon click or a forwarded second launch,
  restores the window correctly every time, with logging confirming every stage completes.
- **Ordering of the Cocoa activation-policy promotion vs. showing the window** — a full reorder
  (promote to Regular + activate first, defer showing/fronting/focusing the window a further EDT
  turn) was implemented and tested and made no observable difference for the notification-click
  case specifically, while it *did* fix a real, separate bug: the same collector's restore-from-tray
  behavior when reached via the single-instance/reopen path. Kept for that reason (it's still live
  in `main.kt`, just no longer reachable from a macOS notification click).

### Workaround

None applied — the currently-working restore paths (tray icon click, relaunching the app) remain
the supported way to bring a tray-hidden window back on macOS.

### What a real fix would need

Bypass AWT's `TrayIcon.displayMessage()`/`ActionListener` for notification display and click
detection on macOS entirely, and drive the Cocoa `NSUserNotificationCenter` (or the modern,
non-deprecated `UserNotifications` framework) directly via a JNA-based Objective-C bridge — in the
same spirit as `MacActivationPolicy`'s existing raw `objc_msgSend` calls, but substantially larger:
it requires creating a runtime Objective-C class (`objc_allocateClassPair`/`class_addMethod` with a
JNA `Callback` as the implementation) to act as the notification center's delegate, which is
inherently higher-risk native interop (a mistake can crash the JVM, the same class of risk as the
Linux GTK crash documented elsewhere in this file) and would need several real-hardware
iterations to get right.

This would very likely become moot rather than worth building, though: `app-architecture.md` notes
macOS is expected to eventually move to a native SwiftUI implementation (`external-spec.md` §2 — a
longer-term, not-yet-scheduled direction, alongside Android and iOS not existing as targets yet). A
native app would use `UNUserNotificationCenterDelegate` through the ordinary app lifecycle — no AWT
bridge, no deprecated API, no Accessory-policy-specific bridging bug — which is a common, reliably
working pattern for macOS menu-bar-only (`LSUIElement`) apps. Given that, and given the AWT-based
version above turned out not to work anyway, building the JNA bridge is deferred indefinitely in
favor of this note, revisited only if the native SwiftUI port itself keeps being deferred long
enough that the tray-hidden gap becomes worth closing on its own.

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

## macOS: clicking exactly at a list row's edge selects the neighbouring row

**Status**: not fixed — it is macOS's own behaviour, reproduced with the same aim in Apple's Notes
app. The app-side geometry it was first blamed on was measured and found exact. The genuine
hit-area defects the investigation *did* uncover are fixed — see "What was actually fixed" below.

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

The investigation started from this report and did find three real defects, all fixed and pinned by
`ListRowHitAreaTest` (`composeApp/src/desktopTest/.../ui/home/`):

- **Dead zones inside the row.** `clip`/`background` used to come *before* the interactive
  modifiers, so hit-testing was clipped to the inset rounded rectangle: the outer margin and the
  four rounded corners selected nothing at all. Worse, for rows that were wrapped in a `Column`
  (to lay the drag insertion marker out as a sibling), any point outside a padded descendant's own
  bounds was dead even though it was squarely inside the `Column`'s reported bounds. Fixed by
  `listRowClickable` / `listRowSurface` (`ui/home/ListRowChrome.kt`), which split the hit area
  (the row's whole band) from the painted highlight (inset and clipped).
- **The gap between two rows split unevenly.** The insertion marker reserved a layout slot, and
  that space belonged entirely to whichever row's layout contained it, so a click closer to one
  row's highlight than to its neighbour's could still resolve to the neighbour. Fixed by painting
  the marker into the row's own margin instead of laying it out (`insertionMarkers`), which puts
  the hit boundary at the gap's true midpoint.
- **Row heights varied with state.** A folder header's band changed height depending on whether it
  was collapsed or the last folder, and a feed row's on whether it was last in its group.

Between them these removed every click that selected *nothing*, which was the other half of the
original report. What remains is only the edge case above.

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
- **Switching UI framework.** A SwiftUI/AppKit port would not help: Notes is native and behaves the
  same way, and the cursor hotspot is decided by the OS.

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
`SQLITE_BUSY_TIMEOUT_MS`, applied via `sqlite_connection_properties()`) only governs waiting to
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
See `docs/testing.md`'s note on concurrent-write tests for the general pattern.

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
