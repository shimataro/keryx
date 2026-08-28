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

## Dialogs flickered on open, briefly showing their content in the wrong place

**Status**: Resolved — by keeping a dialog's OS window invisible until the drift guard reports its
geometry is final (`DialogFitDecision.presentable`), and by pre-filling the native window's own
background with the dialog's container color. Third in the same family as "Dialogs occasionally
opened at an unexpected size" and "Linux: modeless dialogs (Settings, About) shrank their window
width to near-zero" above — the same `DesktopModalWindow` auto-fit machinery — but on a path neither
of those touched: those two were about the size the dialog *ends up* at, this one about what is on
screen on the way there. Kept in full because the evidence is Compose Desktop internals read out of
the sources jar, which would otherwise have to be re-derived.

### Symptom

Opening a dialog *sometimes* flickered. Reported on macOS in **both light and dark mode** (more
obvious in dark), and on **every** dialog — Settings, About, add feed, rename, the delete
confirmations — i.e. everything built on the shared `DesktopModalWindow` /`KeryxAlertDialog` /
`KeryxTabDialog` infrastructure in `ui/common/KeryxDialogs.desktop.kt`.

Not the whole dialog at once. The reporter's description was that **a component appeared somewhere
else for an instant** before settling, and in dark mode there was additionally a brief light band.
Intermittent, and gone as soon as the dialog had settled.

### Diagnosis

The geometry jump — not the color — was the main event, and it is structural rather than
theme-dependent, which is why it reproduced in light mode too.

`DesktopModalWindow` always creates its window at `placeholderSize(initialWidth)` = the dialog's
fixed width x a **240dp placeholder height**, and `resolvePosition` centers it over the owner
*using that placeholder size*. Correcting both to the real fitted size is the drift guard's job,
and it runs afterwards. Reading `ui-desktop-1.11.1-sources.jar` pins down what happens in between:

| Step | Compose Desktop code | Effect |
| --- | --- | --- |
| 1 | `AwtWindow`'s `DisposableEffect(Unit)` → `create()` | `ComposeDialog` constructed; `setContent` only stores the lambda |
| 2 | `UpdateEffect` → `SwingDialog.update` → `setSizeSafely` | window is not displayable, so `pack()` ("Pack to allow drawing the first frame") → `ComposePanel.addNotify()` → **the composition and first measure happen here** |
| 3 | `if (!wasDisplayable && it.isDisplayable) it.renderImmediately()` | the 240dp placeholder frame is drawn |
| 4 | `AwtWindow`'s `DisposableEffect(visible)` → `GlobalScope.launch(MainUIDispatcher) { window().isVisible = true }` | **the window becomes visible on a separate EDT tick** |
| 5 | Keryx's drift guard receives `capturedContentPx` → `applyWindowGeometry` | resize to the fitted height **and re-center against the fitted size** |

The order of 4 and 5 is pure scheduling — the same kind of race as the two entries above, hence the
"sometimes". When 4 wins, the placeholder-sized, placeholder-*centered* frame is on screen for a
moment before 5 lands. Because the card is laid out `Alignment.TopCenter` inside the window, a
window that grows from 240dp to a fitted height H while re-centering moves the card's top edge up by
`(H - 240) / 2` dp — 130dp for a 500dp-tall dialog. That is the reported "a component appeared
somewhere else".

`KeryxTabDialog` (Settings) passes `repositionOnResize = false`, but it is not exempt: on the very
first tick `nextDialogFit` returns `applyPosition = !state.positionApplied`, i.e. `true`, so it
re-centers exactly once — which is the once that matters here.

Three secondary contributors accounted for the color part of the report, and for the same flicker
occurring on a *already-visible* dialog whose content changes size (the add-feed candidate list
appearing, a Settings tab switch, a rename dialog's supporting text coming and going):

- **The dialog's native AWT window background was never painted with the theme color.**
  `main.kt` does exactly this for the main window, for exactly this reason ("Paint the native
  window/content-pane with the theme surface so a dark-mode launch doesn't flash the
  platform-default (light) background"), but `DesktopModalWindow`'s `remember(window)` block only
  set `minimumSize` and the macOS `apple.awt.*` client properties. Any area a resize newly exposes
  is therefore painted by the Look & Feel's default — light — until Compose repaints it.
- **The native button row punches a transparent hole in the Compose canvas.** `NativeButtonRow`
  embeds real `JButton`s through `SwingPanel`, and upstream `SwingInteropViewHolder.init` clears
  that rectangle with `BlendMode.Clear`, so neither the full-bleed `Box` nor the card's `Surface`
  paints it. `SwingInteropViewHolder.layoutAccordingTo` then schedules the bounds update
  asynchronously (`container.scheduleUpdate`), and `SwingInteropContainer.executeScheduledUpdates()`
  finishes with `root.validate(); root.repaint()` over the whole dialog root. During that window the
  hole shows whatever is underneath — i.e. the unpainted native background above. This is a
  *consequence* of the previous item, not an independent cause.
- **The full-bleed background and the card were different colors.** The outer `Box` was hardcoded to
  `surfaceContainerLow` while `KeryxAlertDialog` defaults its card to `surface` and `KeryxTabDialog`
  hardcoded `surface`, so any surplus between the window's current size and the measured content
  showed as a distinctly toned band while the size settled (`#141218` vs `#1D1B20` in the M3 dark
  scheme).

A fourth finding is a feedback loop rather than a paint problem: `NativeButtonRow`'s `SwingPanel`
`update` block called `panel.revalidate()` **unconditionally**. Callers pass non-remembered
`onConfirm` / `onDismissRequest` lambdas, so Compose can never skip that block — it runs on every
recomposition of the parent, e.g. on every keystroke in `TextPromptDialog` (where `confirmEnabled`
flips). `SwingInteropViewGroup.invalidate()` calls back into `layoutNode.invalidateMeasurements()`,
and `AwtContentMeasurePolicy` measures the node from `component.preferredSize`, so each of those
could run: Compose re-measure → window resize → `componentResized` → another drift-guard tick →
`SwingPanel` re-placement → `root.validate()/repaint()`.

### Ruled out

- **"It's just a dark-mode color flash"** — disproved by the reporter seeing it in light mode as
  well, and by the description being about position rather than color. The color contributors above
  are real but secondary.
- **`SwingPanel`'s `background` parameter defaulting to `Color.White` actually painting white** —
  disproved by reading `SwingPanel.desktop.kt`: its own update block runs
  `it.background = background.toAwtColor()` and then calls the caller's `update` **within the same
  invocation**, and Keryx's update sets `panel.background = awtBackground` there, so no frame is
  ever painted with the default. Passing `background` explicitly was still adopted, as insurance
  against that ordering rather than as the fix.
- **Something specific to one dialog, or to the `modal = false` branch** — the discriminator that
  identified the Linux width-collapse entry above does not apply here: this reproduces on the modal
  `KeryxAlertDialog` dialogs (rename, delete confirmation) just as much as on Settings/About.

### Workarounds that did not work / were rejected

- **Opening the window at a better-guessed placeholder height** — cannot work in general: the fitted
  height is not knowable before the content is measured, and per the table above the measurement
  only happens once `pack()` has realized the peer. A per-dialog hardcoded guess would also have to
  be re-tuned for every font-scale setting and locale.
- **Hiding the window again for post-show, content-driven resizes** (candidate list appearing, tab
  switch) — rejected: it would trade a small jump for a full disappear/reappear, and it directly
  conflicts with the deliberate existing behavior that the Settings dialog's top edge must not move
  when a tab changes its height. Those resizes are instead covered by the background/color fixes,
  which is what makes them paint cleanly rather than not happen.
- **Re-asserting the geometry more aggressively before showing** — already ruled out by the first
  entry in this family: a same-value write is a no-op at two independent layers, so it only ever
  polls. The gate below waits on the guard's own decision instead of racing it.

### How this was resolved

- `DialogFitDecision` gained a **`presentable`** flag (`WindowGeometry.desktop.kt`), true when there
  is nothing left to correct — either the window already matches the target, or the correction
  budget is spent. `DesktopModalWindow` passes `visible = readyToShow` to both `DialogWindow`
  overloads and flips `readyToShow` the first time the guard reports `presentable`, calling
  `window.renderImmediately()` immediately before so the first *visible* frame is already the fitted
  one. This is the same API Compose Desktop itself uses in step 3 above, for the same stated reason,
  moved from the placeholder frame to the fitted frame. Everything the table's steps 1-3 and 5 do
  now happens while the window is invisible, so the placeholder frame can no longer reach the
  screen. `presentable` is deliberately also true in the gave-up case, so a window manager that
  refuses the requested geometry cannot leave a dialog invisible forever, and a
  `DIALOG_PRESENT_FALLBACK_MS` (500ms) `LaunchedEffect` shows it regardless if content never reports
  a usable height at all.
- `DesktopModalWindow` gained a **`containerColor`** parameter (default `Color.Unspecified` →
  the theme's `surface`), resolved inside its own `KeryxTheme` scope and used for both the full-bleed
  `Box` (replacing the hardcoded `surfaceContainerLow`, closing the tonal-band gap) and the native
  `window.background` / `contentPane.background`, mirroring `main.kt`'s technique. Applied
  synchronously from a `remember(resolvedColor)` during composition rather than a `LaunchedEffect`,
  the same "as early as possible" reasoning `main.kt` records, and keyed on the color so a runtime
  theme switch follows. `KeryxAlertDialog` forwards its own `containerColor` through unchanged;
  `KeryxTabDialog` needed no change, since the default resolves to the `surface` it already
  hardcoded.
- `NativeButtonRow` now passes `background = backgroundColor` to `SwingPanel` explicitly (insurance,
  see "Ruled out"), and calls `revalidate()` **only when a layout-affecting value actually changed**
  since the previous `update` — the confirm label, or the dismiss label (which is also what decides
  whether the cancel button is shown). `isEnabled` / `foreground` changes are repaint-only, so a
  keystroke no longer reaches `invalidateMeasurements()` at all. The previous values are held in a
  plain, deliberately non-snapshot holder: `SwingPanel`'s update runs inside
  `InteropViewHolder`'s `SnapshotStateObserver.observeReads`, so a `mutableStateOf` there would
  register an observation and writing it would schedule another interop update — another
  `SwingInteropContainer` root `validate()`/`repaint()`, exactly the work being removed.
- `WindowGeometryTest` covers `presentable` (withheld while a correction is pending, true once the
  size lands, true once the attempt cap is spent, true within `FIT_TOLERANCE`). The background
  pre-fill and the conditional `revalidate()` act on a real native peer and are not extractable as
  pure functions, so they stay manual checks in `docs/testing.md`, for the same reason recorded
  there for applying a size to a real `DialogWindow`.

### Residual limitation

A dialog that is **already visible** and then changes size because its content did (the add-feed
candidate list, a text-prompt dialog's supporting text) still resizes on screen, by design — see the
rejected workaround above. What changed for that case is only that the newly exposed area, and the
button row's interop hole, now paint the dialog's own color instead of the Look & Feel's default.
That such a resize also briefly displaces the content on macOS was a separate defect — see the entry
below, which removed the Settings dialog's own tab-switch resize entirely rather than trying to make
a resize artifact-free.

## macOS: switching Settings tabs jumped the content up toward the window's top edge

**Status**: Resolved — by giving `KeryxTabDialog`'s tab-content area a **fixed height**
(`KERYX_TAB_DIALOG_CONTENT_HEIGHT` in `ui/common/KeryxDialogs.desktop.kt`), so switching tabs no
longer resizes the OS window at all. Fourth in the same family as the three entries above, and
specifically what the previous entry's "Residual limitation" left open: those were about the size a
dialog ends up at and what is on screen while it opens, this one about what is on screen while an
*already visible* dialog resizes. Kept in full because the obvious repair was tried first and made
the defect permanent instead of fixing it, and because the evidence is skiko's native code read out
of the shipped dylib, which would otherwise have to be re-derived.

### Symptom

Switching tabs in the Settings dialog flickered: the tab labels — and the whole card with them —
appeared shifted up toward the window's top edge for an instant, then dropped back into place.
Reported on macOS. Not on the dialog's initial open (covered by the invisible-until-fitted gate
above), only on a tab switch of an already-visible dialog, and most visible between the two tabs
whose heights differ most.

### Diagnosis

The dialog's height used to follow the selected tab's content, and the order of operations is forced
by construction:

1. The tab switches, and the new tab's content is composed, measured and **drawn into a window that
   is still the previous tab's height**.
2. `onSizeChanged` / `onGloballyPositioned` publish that content height, so the drift guard's
   `snapshotFlow` collector runs only *after* that frame.
3. The guard computes the new target and applies it with `setBounds`.

So the resize always trails a frame drawn for the wrong window height. What makes that visible on
macOS is where the Skia surface lives. Disassembling the shipped `libskiko-macos-arm64.dylib`
(0.144.6) shows `createMetalDevice` adding an `AWTMetalLayer` (a `CAMetalLayer`) as a **sublayer of
the AWT content view's layer**, with `autoresizingMask = kCALayerWidthSizable|kCALayerHeightSizable`
and `contentsGravity = kCAGravityTopLeft`, and `resizeLayers` — which sets `layer.frame` and
`drawableSize`, and is the only thing in skiko that ever touches that frame — reached exclusively
from `MetalRedrawer.syncBounds`, which derives the frame from **Java-side** AWT bounds
(`y = rootPane.height - globalPosition.y - layer.height`).

Meanwhile `setBounds` reaches the real NSWindow asynchronously: `LWWindowPeer.setBounds` only
forwards to the platform window, deliberately leaving its own bounds stale ("Native system could
constraint bounds, so the peer would be updated in the callback"), and `CPlatformWindow.setBounds`
executes `nativeSetNSWindowBounds` off the EDT. `syncBounds` has exactly four call sites in all of
skiko — `backedLayer.reshape`, a showing-state flip, redrawer re-creation, and a DIRECT3D-only branch
of `SkiaLayer.reshape` — and **none of them observes the native resize**; the layer's frame is only
ever recomputed from an AWT layout pass. Between the window changing size on screen and the next
layout pass, the layer's geometry therefore belongs to the previous size, and its top-pinned contents
can sit above the window's top edge. That is the jump.

### The obvious repair makes it permanent — do not retry

Forcing the layout pass right after the resize (`window.validate()` then `window.renderImmediately()`,
directly after `applyWindowGeometry`) is what skiko itself does in `SkiaLayer.reshape` — and it is
fenced off there for a reason: that pair runs only `if (renderApi == GraphicsApi.DIRECT3D &&
isShowing)`, under a comment noting it "actually causes the reverse glitch". On Metal the reverse
glitch is not transient:

- `validate()` runs while the NSWindow is still the old size, so `syncBounds` sets the layer's frame
  to the new height inside a still-old superlayer — i.e. a **negative top margin** of Δ.
- CALayer springs-and-struts preserve both Y margins, so when the native resize lands the layer's
  height absorbs Δ **again**: the frame becomes `newH + Δ` while `drawableSize` stays `newH`. With
  `kCAGravityTopLeft` the contents end up pinned Δ above the window's top edge, with a Δ-tall band of
  bare window background at the bottom.
- Nothing repairs it. AWT *does* post `COMPONENT_RESIZED` when the native resize lands (the peer's own
  bounds were still stale, so `notifyReshape` skips its "everything is in sync" early return), and
  `java.awt.Window.dispatchEventImpl` does `invalidate(); validate();` — but every Java-side bound
  already holds its final value, so `Container.validateTree` does not recurse into the (valid)
  children, `SkiaLayer.doLayout` never runs again, and `syncBounds` is never called again.

Observed exactly that way: the tab bar and the macOS merged title row cut off above the window's top
edge, a background-coloured band at the bottom, a different offset on each switch, and no recovery
short of switching tabs again or reopening the dialog. It turned a one-frame flicker into a defect
that persisted for as long as the tab was shown.

`renderImmediately()` cannot help on its own either: it only draws (`SkiaLayer.renderImmediately` →
`Redrawer.renderImmediately`, i.e. `update()` + `performDraw()` in `MetalRedrawer`) and re-syncs
neither the layer's frame nor the scene's size. Its documented contract is the one-shot frame drawn
while a window is displayable but not yet visible — which is the only way this file still uses it
(the `presentable` gate above).

### Ruled out

- **The Material 3 tab-bar migration** (`SecondaryScrollableTabRow`, commit `6fb2c15`) — the first
  suspect, since the report followed it. `ScrollableTabRowImpl` derives its height from a `max` over
  *all* tabs' intrinsic heights, so the row is a constant 72dp whichever tab is selected, and its
  `onLaidOut` scrolls the tab row, never the window. The per-tab height fit it appears to interact
  with predates the repo's first commit.
- **The macOS merged title row vanishing for a frame** — it is exactly 28dp, and the report was about
  the tab labels moving to the top, so this looked promising. It cannot happen: the row is drawn
  whenever `selectedLabel != null`, and `SettingsDialog`'s `onSelectTab` can only ever pass an id that
  is in `tabs`.
- **A second resize path** — Compose's own `SwingDialog.update` → `setSizeSafely` does re-run on every
  tab switch (the window title follows the selected tab), but it requests the size the guard has just
  applied, so `Component.reshape` early-returns and no native call happens.
- **Animating the resize** — every step has the same one-frame-late layer, so it smears the artifact
  rather than removing it.
- **Hiding the window across the resize** — rejected in the entry above: a disappear/reappear is worse
  than the jump.

### How this was resolved

`KeryxTabDialog`'s tab-content area now has a fixed height (`KERYX_TAB_DIALOG_CONTENT_HEIGHT`, 416dp
— the tallest tab's natural content height at `fontScale = 1.0`, plus slack), so every tab measures
the same and the window is never resized on a tab switch. Since the artifact is inseparable from
resizing an already-visible window, and forcing the ordering makes it worse, removing the resize is
the only fix available from application code.

The trade-off is deliberate: the shortest tab (notifications, under 100dp of content) shows most of
the area as the dialog's own background. Content taller than the area — a tab that grows later, or a
large `fontSizeScale` — scrolls in the `verticalScroll` that was already there, so outgrowing the
constant degrades gracefully rather than breaking, and bumping it is a cosmetic follow-up.

Growing the window to the tallest tab *visited so far* was considered and rejected as no better in
practice: `general` is both the tallest tab and the one the dialog opens on, so the window would
reach the same height on the first frame anyway, while still resizing in the notification-deep-link
case.

### Residual limitation

Every other dialog still resizes while visible — the add-feed dialog when its candidate list appears,
a text-prompt dialog when its supporting text comes and goes — and can still show the same one-frame
displacement on macOS. It is much less noticeable there (those resizes happen once, in response to
typing, rather than repeatedly on a two-click tab switch), and this fix does not generalize to them:
they have no fixed set of states to size for.

## Windows: the article reader's WebView never rendered, and clicking anywhere froze the app

**Status**: Resolved — by setting an explicit, writable `desktopWebSettings.dataDirectory` for the
reader's WebView (`ArticleWebView` in `ArticleDetailPane.kt`), applied on all three desktop
platforms rather than gated to Windows, since the library reads the same parameter uniformly and
macOS/Linux were only unaffected because their own implicit defaults happened to already be
writable.

### Symptom

On Windows, a white rectangle roughly the width of the article-detail pane appeared over the feed
list pane instead, and the reader area itself stayed blank — no article content, not even the "no
article selected" placeholder the reader renders as HTML inside itself. Clicking anywhere in the
window after that point stopped the app responding entirely. Not reproducible on macOS or Linux.

### Diagnosis

Running with `WRYWEBVIEW_LOG=1` (`io.github.kdroidfilter:composewebview`'s own env-gated logging)
surfaced the real failure, one level below where the visible symptom suggested:

```text
[WryWebViewPanel] createIfNeeded handle=459234 parentIsWindow=true size=280x291
Exception in thread "AWT-EventQueue-0" io.github.kdroidfilter.webview.wry.WebViewException$WryException:
v1=WebView2 error: WindowsError(Error { code: HRESULT(0x80070005), message: "Access Denied." })
        at ... NativeBindings.createWebview-E7Fn0XA(WryWebViewPanel.kt:787)
        at ... WryWebViewPanel.createIfNeeded(WryWebViewPanel.kt:398)
        at ... WryWebViewPanel.scheduleCreateIfNeeded$lambda$0(WryWebViewPanel.kt:589)
        at java.desktop/javax.swing.Timer.fireActionPerformed(Timer.java:289)
        ...
        at java.desktop/java.awt.EventDispatchThread.run(EventDispatchThread.java:90)
```

The native WebView2 surface was never created at all — the position was never wrong, because no
positioning call (`WryWebViewPanel.updateBounds()`'s `setBounds`) ever ran; it early-returns while
`webviewId` is null. The white rectangle was the plain `java.awt.Canvas` the library hosts the
native surface in (Windows is the one platform where `SkikoInterop.createHost()` returns a bare
`Canvas` rather than a Skiko `HardwareLayer`, since WebView2 is positioned by mirroring the
top-level window's HWND rather than parented directly — see `resolveParentHandle()`/
`boundsInParent()`), with nothing ever drawn into it.

**Root cause**: `PlatformWebSettings.DesktopWebSettings.dataDirectory` defaults to `null`, and
Keryx's `ArticleWebView` never set it. With no explicit directory, WebView2 tries to create its own
data folder next to the host executable — here `C:\Program Files\Java\jdk-25.0.4\bin\java.exe`
(from the startup log's `Acquired single-instance lock; running as primary instance from ...`
line), a location a standard user cannot write to, hence `HRESULT(0x80070005)`. This matches
upstream [kdroidFilter/ComposeNativeWebview#31](https://github.com/kdroidFilter/ComposeNativeWebview/issues/31)
exactly — same exception class, same call chain, same HRESULT — including a contributor comment
confirming the same fix (set `dataDirectory` to a writable path). `dataDirectory` is not
OS-conditional in the library (`WebViewDesktop.kt`'s `defaultWebViewFactory` passes it to
`NativeWebView(...)` on every branch), so macOS/Linux were only unaffected because their platforms'
own implicit default directories happened to already be writable.

That single cause explains the freeze too. `WryWebViewPanel.createIfNeeded()`'s non-macOS path
wraps `NativeBindings.createWebview(...)` in `catch (e: RuntimeException)`, but `javap` on the
library jar confirms `WebViewException` extends `java.lang.Exception` directly, not
`RuntimeException` — so that catch clause never actually catches it, and the exception reaches the
EDT uncaught (the "Exception in thread AWT-EventQueue-0" above is AWT's own default uncaught-handler
output). Because the exception aborts `createIfNeeded()` before `stopCreateTimer()` runs,
`scheduleCreateIfNeeded()`'s 100ms `javax.swing.Timer` never stops, and keeps retrying the failing
WebView2 creation call indefinitely on the EDT, which is consistent with the EDT becoming
unresponsive to new input.

### Ruled out

- **A Windows-specific bounds/positioning bug** — the initial hypothesis, based on the visible
  symptom alone, before `WRYWEBVIEW_LOG=1` was available: Windows is the only platform where
  `WryWebViewPanel` mirrors the native surface's position manually against the top-level window's
  HWND (`resolveParentHandle()` returns `parentIsWindow=true`, and `boundsInParent()` mirrors
  `convertPoint(host, 0, 0, window) - window.insets`), which looked like a plausible source of a
  Compose-interop-vs-native-position race. Ruled out once the log showed `updateBounds()`/
  `setBounds` never ran at all — there was no position to be wrong, because there was no native
  surface. Verified fixed after the `dataDirectory` change: the retested log shows
  `createIfNeeded success`, `setBounds` firing once with the correct pane-relative position, and no
  freeze on click.

### How this was resolved

`ArticleWebView` now sets `webViewState.webSettings.desktopWebSettings.dataDirectory` to
`AppDirs.cacheDir()` + a `webview` subdirectory, via `remember(webViewState) { ... }` right after
`rememberWebViewStateWithHTMLData(...)` and before the `WebView(...)` composable is reached.
This has to run synchronously (not from a `LaunchedEffect`) and before the first composition of
`WebView(...)`: the underlying `WryWebViewPanel`'s `dataDirectory` field is `private final`,
captured once when `ActualWebView`'s `remember(state, factory) { factory(...) }` constructs the
native panel, and never re-read afterward. The directory is left for the native library to create
(no `mkdirs()` on the Keryx side) and persists across restarts — unlike the fix suggested upstream,
which uses a fresh timestamped temp directory on every launch — so cookies/local storage for
embedded content (e.g. SNS embeds) survive a restart instead of being thrown away each time. No OS
branch was needed: the property is applied identically on macOS/Linux, which is harmless there
since it just replaces an already-writable implicit default with an explicit one.
`webViewDataDirectory(cacheDir: String): String`, the path-joining logic, is covered by
`ArticleReaderDataDirectoryTest.kt` in `commonTest`.

## Windows: context menus opened in the wrong place with their labels overlapping

**Status**: Resolved — by moving Windows off `java.awt.PopupMenu` onto `javax.swing.JPopupMenu`,
for both the context menus (`platform/NativeMenu.desktop.kt`'s `defaultPopupHandle`) and the tray
menu (the new `tray/WindowsTray.kt`, replacing Compose's `Tray()` there). macOS stays on AWT, where
it is a real `NSMenu`.

### Symptom

On Windows, right-clicking an article row or a feed row opened the context menu **up and to the
left** of the cursor, and its entries were painted on top of each other — the box was wide enough
for the labels but only a fraction of the height needed for them, so consecutive labels overlapped.
Both symptoms scaled with the desktop's display-scaling setting and disappeared at 100%. Not
reproducible on macOS or Linux.

### Diagnosis

Both symptoms are the same underlying JDK defect: the Windows AWT menu peer never converts between
Java's user space (logical, 96-DPI-relative) and device pixels.

**Position** — `awt_PopupMenu.cpp`'s `AwtPopupMenu::Show` takes the x/y straight off the Java
`Event` and hands them to Win32 APIs that work in device pixels, with no `ScaleUpX`/`ScaleUpY`
anywhere in the function:

```cpp
pt.x = env->GetIntField(event, AwtEvent::xID);   // Java user space
pt.y = env->GetIntField(event, AwtEvent::yID);
::MapWindowPoints(awtOrigin->GetHWnd(), 0, (LPPOINT)&pt, 1);   // device pixels
::TrackPopupMenu(GetHMenu(), flags, pt.x, pt.y, 0, awtOrigin->GetHWnd(), NULL);
```

The omission is specific to this path, not a convention: `awt_Component.cpp` does
`ReshapeNoScale(ScaleUpX(x), ScaleUpY(y), ...)`, and `WFontMetrics`'s natives `ScaleDownX` their
results on the way back to Java. The menu therefore lands at `windowOrigin + clickOffset / scale`,
so the error grows with distance from the window's origin. Solving `menu = origin + (click −
origin) / S` against the two reported screenshots gives a consistent `S = 2.0` horizontally and
vertically, with one shared origin — i.e. the reporter's desktop was at 200% scaling.

**Overlap** — `awt_MenuItem.cpp`'s `AwtMenuItem::MeasureSelf` mixes the two spaces within one
struct:

```cpp
int height = JNU_CallMethodByName(env, 0, fontMetrics, "getHeight", "()I").i;
measureInfo.itemHeight  = height;          // user space
measureInfo.itemHeight += measureInfo.itemHeight/3;
measureInfo.itemWidth   = size.cx;         // getMFStringSize -> device pixels
```

`FontMetrics.getHeight()` is `ScaleDownY`'d on the way out of `awt_Font.cpp`, so `itemHeight` — which
Windows reads as device pixels — is `1 / scale` too small, while `DrawSelf` paints with an HFONT
whose `lfHeight` *is* `ScaleUpY`'d (`awt_Font.cpp`, `CreateHFont_sub`). Width comes from
`getMFStringSize`, which is already in device pixels. That asymmetry is exactly the reported
picture: **wide enough, but only a fraction as tall as its own glyphs.**

Upstream: [JDK-8259913](https://bugs.openjdk.org/browse/JDK-8259913) *AWT menu items are not scaled
correctly on Windows HiDPI displays* (unresolved). It is filed against 300%+, but the arithmetic
above breaks at every scale over 100%, proportionally.

The decisive cross-check was inside a single screenshot: the app's own menu bar rendered perfectly
at the same DPI, because Compose Desktop's `MenuBar` builds a `javax.swing.JMenuBar` (confirmed by
disassembling `ui-desktop`: `MenuBarScope.setContent(javax.swing.JMenuBar, ...)`). Swing paints
through Java2D with the transform applied and is correct at any scale; AWT's menu peer is not.

### Ruled out

- **A bug in Keryx's own coordinate conversion** (`Modifier.nativeContextMenu`'s
  `(elementPosition + localPosition) / density.density`). That division is the documented contract
  of `java.awt.PopupMenu.show`, which takes coordinates in the invoker's user space; Compose
  Desktop's pointer positions are `userSpace × density`. It is also why macOS is fine — AppKit is
  point-based, so `NSMenu` consumes exactly what this produces. No app-side arithmetic change was
  needed or made.
- **Compensating for the scale factor in Keryx** (multiplying x/y back up before `show`). Rejected:
  it could only fix the position. The overlapping labels happen inside the JDK's owner-draw measure
  callback, which no Java-side call can reach. Shrinking the menu font to make the rows fit was
  rejected for the same reason — it trades one rendering defect for another.
- **The `java.awt.CheckboxMenuItem` change** that introduced real check gutters. It was a suspect
  because the feed-row menu is full of them, but the overlap reproduces just as well on the tag-row
  menu, which has none.

### How this was resolved

`defaultPopupHandle` now selects `AwtPopupHandle` **only on macOS**; Windows joins Linux on
`SwingPopupHandle`. The selection is a `macOs` parameter defaulting to the process constant purely
so `NativeMenuTest` can pin the mapping on any CI host. Two behaviours follow the backend and are
intentional: separators become a real `JPopupMenu.Separator` instead of a `"-"`-labelled
`MenuItem`, and modifier-less shortcuts (F2 / Delete) — which `java.awt.MenuShortcut` structurally
cannot express — now render in the accelerator column. `forceHeavyweight` becomes load-bearing on
Windows in a way it was not on Linux: FlatLaf forces heavyweight popups there anyway, but Windows
takes the system-L&F branch in `installLookAndFeel`, so that one line is what keeps the menu from
being drawn behind the article reader's WebView.

The tray was fixed in the same pass, because Compose's `Tray()` builds a `java.awt.PopupMenu` of its
own (also confirmed by disassembly) and so squashed its two entries identically. `WindowsTray`
drives a raw `TrayIcon` and opens a `WindowsTrayMenu` (Swing) on right-click, the same shape
`MacTray` already uses to bypass `Tray()` for an unrelated reason. `TrayIcon.addActionListener` is
kept wired to `onTrayAction` so `shouldHideOnTrayAction`'s heuristic sees exactly the events it did
before, and notifications go out through `TrayIcon.displayMessage`, which is what
`TrayState.sendNotification` called anyway. Its invoker frame differs from `MacTray`'s in one way
worth keeping: it is focusable and hidden between uses, because an AWT `PopupMenu` runs its own
native modal loop and dismisses itself, whereas a `JPopupMenu` closes on an outside click only if
its owning window can hold — and lose — focus.

### The tray needed a second fix: `TrayIcon`'s own coordinates

Swapping the widgets fixed the tray menu's *rendering* but not its *position* — it still opened
clipped against the right screen edge, however far left the icon actually was. This is the same
defect pattern in a third place, and it is worth recording separately, because nothing about the
menu backend caused it.

`AwtTrayIcon::WmAwtTrayNotify` takes a raw `::GetCursorPos()` result — device pixels — and passes it
to `SendMouseEvent`, which stores it as *both* the component-relative and the on-screen coordinate
pair (`x, y, // no client area coordinates` / `x, y`). No `ScaleDownX/Y` appears anywhere on that
path, so **`TrayIcon`'s `MouseEvent.getXOnScreen()` is in device pixels on Windows**, while
`java.awt.Window.setLocation` takes user space and scales it back up internally. Parking the invoker
window at the event's own numbers therefore placed it `scale` times too far out — past the screen
edge entirely, since the tray already sits at the bottom right, after which `JPopupMenu`'s own
on-screen correction pinned the menu to the edge.

The fix is to take the position from `MouseInfo` instead (`trayMenuAnchor` in `WindowsTray.kt`).
`Java_sun_awt_windows_WMouseInfoPeer_fillPointWithCoords` reads the same `::GetCursorPos()` but
resolves the monitor under it with `MonitorFromPoint` and returns
`AwtWin32GraphicsDevice::ScaleDownAbsX/Y(pt)` — a per-monitor divide about that monitor's own origin
(`screen + ClipRound((x - screen) / scaleX)`). That is both the space `setLocation` wants and
correct when monitors have different scale factors, so no scale factor has to be derived app-side.

macOS needs none of this: `CTrayIcon` reports points throughout, which is why `MacTray`'s
structurally identical `e.xOnScreen - origin.x` arithmetic is correct as written and was left alone.

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
