---
name: ui-guidelines
description: Keryx UI/Compose style guidelines. Read when adding or modifying Compose under `ui/` (home / common / settings / setup / article) or `platform/NativeMenu`. Defines pane tonal roles, divider policy, article card style, layout stability, flat native-feel components (buttons / toggles / text fields / overflow menus), dialog/popup conventions, and icon usage rules.
---

# UI Guidelines (Compose Multiplatform)

Style conventions for Keryx's Compose UI. Follow these when adding or modifying
UI anywhere under `ui/` — the Home screen's 3-pane layout and list rows
(`ui/home/`), the shared flat controls (`ui/common/`), settings (`ui/settings/`),
setup (`ui/setup/`), the article reader (`ui/article/`), and the native
overflow menu (`platform/NativeMenu`).

## Layout stability under state changes

**A value's on/off state must never change a sibling's position, and must
never add/remove a layout slot.** If an element (icon, badge, indicator) only
sometimes renders based on data (e.g. starred/unread/error), always reserve
its layout space unconditionally and make only the *innermost* content
conditional — never wrap the reservation itself in an `if`, and never let a
conditional element sit as a plain sibling in a `Row`/`Column` next to
content whose position must stay fixed (e.g. a title `Text`).

- Bad: `if (starred) { Icon(...); Spacer(...) }` before a `Text` — the `Text`
  shifts horizontally by the icon+spacer width depending on `starred`.
- Good: an always-present `Box`/fixed-size slot that conditionally shows the
  `Icon` inside it, so the slot's size never depends on the condition.
- This applies to *aggregate* size too, not just presence: stacking two
  conditional slots in one `Column` (e.g. "star slot on top, dot slot below")
  and centering the whole `Column` still shifts the *dot's* position from
  where it sat before the star slot existed, because centering an 18dp-tall
  column is not the same as centering an 8dp one. Prefer one `Box` sized to
  a stable reference (e.g. the row height or the favicon height) with each
  indicator placed via its own `Modifier.align(...)`, so each indicator's
  position is independent of whether its siblings are shown.
- Real incident: `ArticleRow`'s star icon used to render conditionally right
  before the title `Text`, shifting the title ~18dp depending on
  `is_starred`. Fixed by moving the star into an always-present indicator
  `Box` (see Article card style below) instead of the title row.
- Compose gotcha to watch for when reserving space like this:
  `Modifier.size(x)` **clamps to the parent's incoming constraints** — if the
  reserved slot has a fixed narrow width (e.g. `8.dp`) and the inner icon
  needs to render *larger* than that width, `Modifier.size(20.dp)` silently
  gets shrunk back down to `8.dp` with no error. Use
  `Modifier.requiredSize(x)` when the child is meant to overflow its
  container's declared bounds.
- **Prefer disabled over hidden.** When a control's availability depends on
  state (an action needs a selection, a precondition isn't met yet, a
  feature is temporarily unavailable), render it in a disabled/inactive
  visual state (lowered opacity, non-interactive, `enabled = false`) rather
  than conditionally hiding it. Hiding a control moves every element after
  it and makes the surrounding layout jump each time the condition flips;
  showing it disabled keeps the layout stable and tells the user the
  feature exists but isn't currently usable, instead of having it vanish
  unpredictably. Reserve actual hide/show (not just disable) for elements
  that are conceptually never relevant in the current context (e.g. a
  Dropbox-only menu item when `CloudStorageAvailability.dropboxAvailable`
  is `false`), not for elements that are merely temporarily inactive.

## Pane structure & tonal roles

The 3 panes (`FeedListPane` / `ArticleListPane` / `ArticleDetailPane`) do not
share a common `TopAppBar`. Each action icon lives in the pane it operates on,
not in global chrome:

- Feed-management icons (add feed / refresh all / cloud sync) — top of
  `FeedListPane`
- Settings — bottom-left of `FeedListPane`
- Article-related icons (search / notifications / sort / mark all read) —
  header row of `ArticleListPane`

Panes are tinted left-to-right with increasingly bright Material3 tonal
surface roles, so boundaries read from tone alone rather than requiring a
hard line: `surfaceContainerLow` (`FeedListPane`) → `surfaceContainer`
(`ArticleListPane`) → `surface` (`ArticleDetailPane`). See
[KeryxTheme.kt](../../../composeApp/src/commonMain/kotlin/works/merc/keryx/app/ui/theme/KeryxTheme.kt)
for the base color scheme and each pane file for where the tone is applied.

The macOS traffic-light inset (`WindowChrome.titleBarInsetDp`) is applied
only to the pane that sits in the window's top-left corner — currently
`FeedListPane`'s header row.

## Divider policy

- **Between panes**: keep `ResizableDivider`, but de-emphasize it — idle
  color `MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)`, hover
  color `MaterialTheme.colorScheme.primary` (signals it's draggable). See
  [ResizableDivider.kt](../../../composeApp/src/commonMain/kotlin/works/merc/keryx/app/ui/home/ResizableDivider.kt).
- **Within a pane, where a fixed control row meets a scrollable area**
  (header → list, list → footer): no `HorizontalDivider()`. Both sides share
  the same tone inside one `Column`, so spacing alone is enough to separate
  them.
- **Semantic section breaks inside a scrollable list** (e.g. `FeedListPane`'s
  "All Feeds/Unread/Starred" → "Tags" → "Feeds" groups) may still use a
  `HorizontalDivider()` — that's a different kind of boundary (grouping
  unrelated list sections), not a fixed-row/scroll-area boundary.
- **Between individual rows in a list** (e.g. article rows): no divider.
  Separate rows with padding and the selection-highlight background
  (`selectionBackground`) instead. The highlight itself is an inset rounded
  rectangle, not a pane-edge-to-pane-edge block: `Modifier.fillMaxWidth()`,
  then `.padding(horizontal = 8.dp, vertical = 2.dp)` (the outer margin) →
  `.clip(MaterialTheme.shapes.small)` → `.background(selectionBackground(...))`
  → the row's existing interactive modifiers (`clickable` /
  `dragAndDropSource` / `dragAndDropTarget`) → the row's inner content
  padding (reduced by 8dp horizontally from its pre-inset value, to keep icon
  positions roughly stable). `clip`/`background` must sit before the
  interactive modifiers so click/drag hit-testing matches the rounded inset,
  not the full row width. For `ArticleRow`, the `.heightIn(min = rowHeight)`
  call must stay *after* the inner content padding (see Article card style
  below) — the outer margin doesn't affect that ordering.

## Sticky section headers in scrollable lists

**Desktop only** — see the note at the end of this section.

A scrollable list whose content is split into distinct, named sections (e.g.
`FeedListPane`'s "フォルダー" and "タグ" groups) pins each section's header to
the top of the scrollable area while scrolling through that section, handing
off to the next section's header once it reaches the top — the same
interaction VSCode's Explorer sidebar uses for stacked sections ("Outline",
"Timeline", etc.). Use `LazyListScope.stickyHeader(key, contentType, content)`
(stable in this project's Compose Multiplatform version, no `@OptIn` needed)
rather than a plain `item(...)` for any such header, following
`FeedListPane`'s "フォルダー"/"タグ" headers as the reference implementation:

- Paint the header's own background with the *same* tonal-role token the pane
  itself uses (e.g. `surfaceContainerLow` for `FeedListPane`, per "Pane
  structure & tonal roles" above), placed between `Modifier.fillMaxWidth()`
  and the row's `Modifier.padding(...)` so it fills edge-to-edge. A
  `stickyHeader` draws above content scrolling beneath it but has no opacity
  of its own — without this, rows scrolling underneath would show through.
- No hairline border, divider, or drop shadow on the header itself — per the
  Divider policy above, a fixed row meeting a scroll area needs no divider
  ("both sides share the same tone… spacing alone is enough"), and a pinned
  header is that same relationship, just achieved by pinning instead of
  static layout. A shadow would also reintroduce the M3 tonal-elevation look
  this app avoids everywhere else.
- A semantic divider between two sections (e.g. `FeedListPane`'s
  `"tags-divider"`) stays a plain, unpinned `item(...)` — do not fold it into
  either section's sticky header. The header's own opaque background already
  separates it from content scrolling beneath; baking a divider into the
  header would show it permanently, even at rest with both sections fully
  visible — exactly the "fixed row/scroll boundary" case the Divider policy
  says needs none.
- This does not change how a list's rows are hit-tested for drag-and-drop
  (`FeedListDragController`/`HomeCommon.resolveHitBand`):
  `LazyListState.layoutInfo.visibleItemsInfo` keeps every item — sticky or
  not — in ascending index order, so a pinned header's band is always
  resolved before any row hidden behind it, with no extra code needed.

**Desktop only.** This convention targets the current Compose Multiplatform
desktop 3-pane layout. Android/iOS mobile targets (see `external-spec.md` §2)
are only planned, not yet built, and a phone-sized layout may not reuse this
same sidebar/list structure at all — defer whether/how sticky section headers
apply on mobile until that layout is actually designed, the same way
`app-architecture.md` already defers the Android icon-set question to when
Android work begins.

## Article card style

`ArticleRow` in
[ArticleRowComponents.kt](../../../composeApp/src/commonMain/kotlin/works/merc/keryx/app/ui/home/ArticleRowComponents.kt):

- Row height and favicon size are computed once per pane (not per row) via
  `rememberArticleRowMetrics()`, from typography `lineHeight`s and density/fontScale
  only — never from per-article content (title length, starred state, etc.). This
  avoids a past bug where `Modifier.height(IntrinsicSize.Min)` + `fillMaxHeight(fraction)`
  produced a few px of row-to-row jitter, because Compose's intrinsic-measurement pass
  gives the weighted title/metadata `Column` a different width share than the real
  measurement pass. Do not reintroduce `IntrinsicSize.Min`/`fillMaxHeight`/`aspectRatio`
  for this row.
- Layout, left to right: unread dot (8dp) → favicon (a Coil `AsyncImage` using
  `ContentScale.Crop` and a `primaryContainer` background chip behind it so
  transparent-background favicons stay visible against the dark theme, falling back to
  `Icons.Filled.Public` on load failure, or a same-size `Spacer` placeholder when there is
  no favicon URL yet — **not** a wrapping `Box`, see "Gaps and node count" below) → a
  column with the title and metadata.
- Title uses `minLines = 2, maxLines = 2` (matching the height computed by
  `rememberArticleRowMetrics()`); `FontWeight.Bold` + `onSurface` when unread, normal
  weight + `onSurfaceVariant` when read.
- Metadata line uses `labelSmall` + `onSurfaceVariant.copy(alpha = 0.7f)`, laid out per the
  "Metadata lines" rule below: the feed name carries `Modifier.weight(1f)` (filling, so the
  timestamp is pinned to the trailing edge and dates line up down the list), then the
  timestamp with an 8.dp leading `Modifier.padding` (not a `Spacer` — see "Gaps and node
  count" below).
- No divider between rows (see Divider policy above).
- Favicon loading goes through Coil3's `AsyncImage`; the `ImageLoader` is
  configured once at startup via `configureImageLoader(...)` in
  [ImageLoaderSetup.kt](../../../composeApp/src/commonMain/kotlin/works/merc/keryx/app/di/ImageLoaderSetup.kt)
  (called from [main.kt](../../../composeApp/src/desktopMain/kotlin/works/merc/keryx/app/main.kt)):
  network fetch via the app's existing `HttpClient`, SVG decoding support,
  and an on-disk cache under `AppDirs.cacheDir()`.

## Gaps and node count

`ArticleRow` sits inside the article list's `LazyColumn`, close to a known
Compose reuse-pool crash threshold that is sensitive to how many `LayoutNode`s
each row has (see [known-issues.md](../../../docs/known-issues.md)). A `Spacer`
is its own composable — its own `LayoutNode` — so a fixed gap between two
elements in this row is a leading/trailing `Modifier.padding` on one of the
elements themselves, never a `Spacer`, wherever a `padding` addition can
reproduce the same visual gap. `Modifier.padding` attaches to the existing
element's `LayoutNode` rather than creating a new one, so this costs nothing
visually while keeping the row's node count down. Do not reintroduce a
`Spacer` here (or in any other `LazyColumn` row) for a plain fixed gap.

This is a workaround for the crash above, not a general style preference — once the upstream
fix lands and the mitigation in `ArticleRow` is reverted (see known-issues.md's re-check
procedure), delete this section and go back to using `Spacer` normally in `LazyColumn` rows.

## Metadata lines

An `A · B` metadata line (feed name · timestamp, author · timestamp) must be a `Row` of
**two** `Text`s — never one joined string. `Row` measures its unweighted children first, so
putting `Modifier.weight(1f)` on the **leading** half only (plus `maxLines = 1` +
`TextOverflow.Ellipsis`) guarantees the trailing half is laid out at its full intrinsic width
and only the leading half ellipsizes. Joining them into one `Text` makes both share a single
ellipsis budget, and a long leading value truncates the trailing one away completely — the
timestamp simply stops being rendered.

Whether the trailing half is pinned to the edge or stays inline is the `fill` flag:

| Context | Weight | Result |
| --- | --- | --- |
| List rows ([ArticleRowComponents.kt](../../../composeApp/src/commonMain/kotlin/works/merc/keryx/app/ui/home/ArticleRowComponents.kt), `ArticleRow`) | `weight(1f)` (fill) | Timestamp pinned to the trailing edge, so dates align down the list |
| Detail header ([ArticleWebViewHtml.kt](../../../composeApp/src/commonMain/kotlin/works/merc/keryx/app/ui/article/ArticleWebViewHtml.kt), `.article-meta`) | n/a (flowing HTML) | Timestamp stays inline right after the leading value |

The detail header is rendered as flowing HTML text inside the article reader's own WebView
(`.article-meta` in [ArticleWebViewHtml.kt](../../../composeApp/src/commonMain/kotlin/works/merc/keryx/app/ui/article/ArticleWebViewHtml.kt)),
not as a Compose `Row` like the list row above — see "Popup vs. Dialog" below for why nothing in
this pane is drawn by Compose. That `div` deliberately has no `white-space`/`text-overflow`/`overflow`
rules — it wraps instead of clipping, so the timestamp is never lost there. Do not add single-line
clamping CSS to it.

When the leading half is optional, the separator travels with the **trailing** `Text`
(`" · $timestamp"`), so it can never dangle after an ellipsized or absent leading value.

## Context menus

Right-click menus use a real OS-native menu, not Material3's
`DropdownMenu`/`DropdownMenuItem` — a Compose-drawn popup never actually looks
native no matter how it's styled. Attach
[`Modifier.nativeContextMenu`](../../../composeApp/src/commonMain/kotlin/works/merc/keryx/app/platform/NativeMenu.kt)
(`platform/NativeMenu.kt`, desktop `actual` in `NativeMenu.desktop.kt`) to the
row or pane the menu belongs to:

```kotlin
Modifier.nativeContextMenu(
    items = {
        listOf(
            NativeMenuItem(someActionLabel) { onSomeAction() },
            NativeCheckMenuItem(tagLabel, checked = isAttached) { onToggleTag() },
            NativeSubMenu(moveLabel, items = folders.map { NativeCheckMenuItem(...) { ... } }),
            NativeMenuSeparator,
            NativeMenuItem(destructiveActionLabel) { onDestructiveAction() },
        )
    },
    onOpen = { if (!selected) onClick() },
)
```

- Entry types: `NativeMenuItem` (plain action, optionally `enabled = false` to
  gray it out rather than omit it), `NativeCheckMenuItem` (action with an
  on/off state), `NativeSubMenu` (nested), and `NativeMenuSeparator` (a plain
  `data object`, no parameters — a visual divider between groups). Use
  `NativeCheckMenuItem` for anything the user toggles — never mark the state in
  the label text yourself; the platform draws its own checkmark. Order and
  group a row's menu to match the equivalent app-menu-bar section when one
  exists (see `AppMenuTree.kt`'s Feed menu), separators included, so the two
  surfaces read as the same menu.
- `onOpen` fires just before the menu shows; call sites typically use it to
  select the right-clicked row. An **empty** `items` list shows no menu and
  makes `onOpen` the only effect — that's how a pane background moves focus on
  right-click without selecting anything.
- The menu's **shape** (the kind of each entry, plus each submenu's child
  count) is expected to be stable per call site across ordinary
  recompositions. It may still change when the underlying data does (a folder
  is added), which rebuilds the native widgets; labels and checked states are
  synced on every change without a rebuild.
- Do not reach for `androidx.compose.material3.DropdownMenu` for this kind of
  menu going forward.

### Backends

The desktop `actual` has two, chosen by platform — see the Look & Feel section
below. macOS/Windows use `java.awt.PopupMenu`, which AWT maps onto a genuine
`NSMenu`/Win32 menu. Linux uses `javax.swing.JPopupMenu`, because AWT's
`PopupMenu` there is a heavyweight XAWT widget that ignores the Swing Look &
Feel entirely and keeps a Motif-era appearance no matter how the app is themed.
Swing popups are forced heavyweight (`isLightWeightPopupEnabled = false`) so
they get their own window and paint *above* the article reader's native WebView
rather than behind it.

## Swing Look & Feel (the non-Compose surfaces)

A few surfaces are drawn by Swing, not Compose: the application menu bar
(Compose's `MenuBar` is a real `JMenuBar` underneath), context menus, and the
dialog button row. Which Look & Feel they get is decided once in
[`ui/theme/DesktopLookAndFeel.kt`](../../../composeApp/src/desktopMain/kotlin/works/merc/keryx/app/ui/theme/DesktopLookAndFeel.kt):

- **macOS / Windows** — the system L&F (`UIManager.getSystemLookAndFeelClassName()`).
  Aqua and the Windows L&F already render these natively.
- **Linux** — FlatLaf, tinted to this app's own theme. The system L&F there is
  Java's GTK2-era emulation, which looks dated next to a modern GTK/Qt desktop.

Two FlatLaf keys carry the whole tint: `@accentColor` (from `keryxAccentColor`)
and `@background` (from `keryxSurfaceColor`). FlatLaf derives the rest —
menu-item selection, checkmarks, focus rings, the default button, menu bar and
popup backgrounds and their borders. **Don't add per-widget color overrides**;
adjust the two source colors in `KeryxTheme.kt` instead. Corner radii are
deliberately not overridden either — FlatLaf's `Button.arc = 6` already matches
`KeryxShapes.small`.

Light/dark follows the in-app theme at runtime (an effect in `main.kt` calls
`updateLookAndFeel`, which re-runs setup and `FlatLaf.updateUI()`), so a theme
change applies without a restart. Anything platform-conditional here keys off
`platform/DesktopOs.kt`'s `isMacOs`/`isLinux` — don't re-derive `os.name`.

## Text input dialogs

`TextPromptDialog` in
[TextPromptDialog.kt](../../../composeApp/src/commonMain/kotlin/works/merc/keryx/app/ui/home/TextPromptDialog.kt)
(shared by the add/edit tag and rename feed dialogs) and the add-feed dialog in
[AddFeedDialog.kt](../../../composeApp/src/commonMain/kotlin/works/merc/keryx/app/ui/home/AddFeedDialog.kt)
follow this pattern for single-field dialogs. **The field itself is
[`KeryxTextField`](../../../composeApp/src/commonMain/kotlin/works/merc/keryx/app/ui/common/KeryxTextField.kt),
not M3's `OutlinedTextField`** — an `expect`/`actual` drop-in (desktop `actual`:
`KeryxTextField.desktop.kt`) that renders a flat, thin-bordered native-feel field
(hairline `outlineVariant` border, `shapes.small`, border → `primary` on focus /
`error` when `isError`) instead of M3's tall outlined box with a floating label.
When an Android target is added, its `actual` should go back to M3's
`OutlinedTextField`/`TextField` (Material is the desirable look there). Don't reach
for `OutlinedTextField`/`TextField`/`BasicTextField` directly at a call site — use
`KeryxTextField`. Its `modifier` param lands on the inner text field, so a
`focusRequester` / `onFocusChanged { it.isFocused }` on it behaves as it did on
`OutlinedTextField`.

- **Validation**: a `blockingError: (String) -> String?` callback returns a
  message that blocks confirm (e.g. duplicate tag name); a separate
  `infoHint: (String) -> String?` returns a non-blocking hint (e.g. "leaving
  this blank resets to the default title"). Either feeds `isError` /
  `supportingText` on the `OutlinedTextField`; both return `null` when there's
  nothing to show.
- **Blank input**: blocked by default; pass `allowBlank = true` when an empty
  value is meaningful (e.g. "reset to default") rather than invalid.
- **Confirm**: also triggerable via IME Done —
  `KeyboardOptions(imeAction = ImeAction.Done)` +
  `KeyboardActions(onDone = { submit() })` — and the confirm button is
  `enabled = false` while validation fails.
- **Autofocus**: a `FocusRequester` + `LaunchedEffect(Unit) { focusRequester.requestFocus() }`
  focuses the field as soon as the dialog appears.
- **Placeholder contrast**: handled inside `KeryxTextField` (placeholder drawn at
  `onSurfaceVariant.copy(alpha = 0.6f)`) — call sites no longer pass a
  `colors = OutlinedTextFieldDefaults.colors(...)` override. Material3's default
  `onSurfaceVariant` placeholder color was too close in brightness to the
  entered-text color (`onSurface`, alpha 1.0) to tell empty-with-hint apart from
  filled-in at a glance; the flat field bakes in the darker placeholder.

## Inline row editing

Renaming a feed, folder, or tag happens **in the row**, not in a dialog: the row's label `Text` is
swapped for
[`InlineRenameField`](../../../composeApp/src/commonMain/kotlin/works/merc/keryx/app/ui/home/InlineRename.kt),
driven by `FeedListPane`'s single `inlineEdit: InlineEditTarget?` state. Any new row kind that
gains a rename should reuse that composable rather than adding another `TextPromptDialog` call, and
should follow the same rules:

- **Same slot, same height.** The editor goes in the *exact* `Modifier.weight(1f)` slot the label
  occupied, so no sibling (chevron, avatar, color dot, error indicator, `CountBadge`) moves when
  editing starts or ends — the "Layout stability under state changes" rule above applies to the
  edit/not-edit flip like any other state. That is what
  `KeryxTextField`'s `minHeight`/`horizontalPadding` parameters are for: `InlineRenameField` passes
  `minHeight = 0.dp` so the field is exactly one text line tall, matching the `Text` it replaced.
  Don't give the editor a `supportingText` — it would add a second line and grow the row. Say it
  with `placeholder` instead (the feed editor shows the feed's own title there, so a blanked
  custom title visibly announces what it falls back to).
- **The field is `KeryxTextField`**, like every other text input (see "Text input dialogs" above).
- **Commit/cancel convention** (desktop file managers): Enter — or the IME `Done` action, so a
  future touch target needs no second path — commits *when valid* and is otherwise swallowed with
  the editor left open; **Escape cancels**; **losing focus commits when valid and silently cancels
  when not** (focus has already moved on, so never drag it back). Every editor also shows a small
  "×" cancel affordance while open, via `KeryxTextField`'s existing `trailingIcon` — Escape has no
  touch equivalent and is undiscoverable even on desktop. Keep the "×" smaller than one line of row
  text so showing it can't change the row's height.
- **Validation is `TextPromptDialog`'s model, not a new one** (`inlineRenameValidation`): a blank
  value is *not* an error — no message, no red frame, it simply cannot be committed — unless
  `allowBlank` says a blank value is itself meaningful. `blockingError` (e.g. the duplicate-name
  check) is what turns the frame red. Per-row-kind differences are real and deliberate: a feed
  title allows blank and has no duplicate check; folder and tag names allow neither.
- **The whole state machine stays platform-agnostic.** `inlineEdit`, commit/cancel, and validation
  know nothing about right-click menus, F2/Return, or hover — starting an edit is just an
  `onRename`/`onEdit` lambda, so a touch long-press could call the same one later.
- **Nothing else may claim the row's pointers or keys while an editor is open.** The feed pane's
  reorder drag watches the `Initial` pointer pass on an ancestor, so it is switched off
  (`feedListReorderDrag(enabled = …)`) while editing, or a press-and-sweep to select text would
  become a row drag. Likewise the pane reports editing focus through `onTextInputFocusChange`, the
  same channel as the search field, which is what makes the root's bare-key shortcuts and the menu
  bar's F2/Delete accelerators stand aside.

Creating still uses a dialog (`FeedListDialogs.kt`'s add folder / add tag): there is no row to edit
in place yet, and a new tag picks its name and color at once.

## Native-feel restyle (flat press feedback, icon set, popovers)

The app does not embed AWT/Swing widgets via `SwingPanel` for ordinary controls
(e.g. `Switch`/dropdowns) — JetBrains Compose Multiplatform has unresolved
z-order/overdraw/crash bugs for `SwingPanel` inside scrollable containers, and
every candidate control here lives inside one (`SettingsScreen`'s
`verticalScroll` `Column`, `FeedListPane`/`ArticleListPane`'s `LazyColumn`).

Three native-widget exceptions exist, all outside scrollable containers, and
**none of them is a pattern to extend**:

- `platform/NativeMenu.kt` — shows a menu on demand rather than embedding a
  persistent Compose-tree node (see above).
- `ui/common/KeryxDialogs.desktop.kt` — the dialog button row (`JButton`s in a
  `SwingPanel`), so confirm/cancel get the platform's real buttons.
- `ui/home/ArticleDetailPane.kt` — the reader `WebView`, a heavyweight AWT
  panel wrapping an OS browser view. This is also *why* every dialog is a
  separate `DialogWindow`: a heavyweight panel always paints over in-window
  Compose `Popup`s.

For everything else the native *feel* comes entirely from Compose-side
theme/shape/indication/icon choices:

- **Flat press feedback, no ripple**: `KeryxTheme.kt` provides a custom
  `IndicationNodeFactory` (`FlatIndication`) via
  `CompositionLocalProvider(LocalIndication provides ...)`, applied once for
  the whole app. It draws an immediate, non-animated `onSurface`-ish low-alpha
  rectangle overlay while pressed — no ripple spread/fade. Don't add
  per-call-site `indication = rememberRipple(...)` (or any other indication)
  overrides going forward; if a control needs a different feel, change
  `FlatIndication` itself so the app stays consistent. `LocalIndication` only
  affects plain `Modifier.clickable`/`selectable`/`toggleable` call sites,
  though — M3 components (`Button`/`IconButton`/`Switch`/`Checkbox`/…)
  hardcode `ripple()` internally and never consult it. `KeryxTheme.kt` also
  provides `CompositionLocalProvider(LocalRippleConfiguration provides ...)`
  with every `RippleAlpha` channel zeroed, as a global safety net for those
  components (there's no public API to disable the ripple *animation* itself,
  but zero alpha makes it invisible). For buttons specifically, prefer
  `ui/common/FlatButtons.kt`'s `FlatButton` (primary/filled — `primary` fill),
  `FlatTonalButton` (secondary — `secondaryContainer` fill + hairline
  `outlineVariant` border, for actions that still need clear button affordance
  like OPML import/export, Dropbox disconnect, update check, setup cards), and
  `FlatTextButton` (bare, inline) over M3's `Button`/`FilledTonalButton`/
  `TextButton` — they're built on plain `Modifier.clickable` so they pick up
  `FlatIndication` directly rather than relying on the `RippleConfiguration`
  fallback. A solid fill (not a transparent outline) is what makes a secondary
  action read as a tactile button rather than a link, so there is intentionally
  no transparent "outlined" flat button.
- **`SegmentedControl<T>` / `ToggleChip`**
  (`ui/common/SegmentedControl.kt`): the replacement for Material3's
  `FilterChip` for both "pick one of N" (`SegmentedControl`, used by
  `SettingsScreen`'s theme/font-size/cache/timeout/refresh-interval rows) and
  standalone boolean toggles (`ToggleChip`, used by `ArticleListPane`'s
  "unread only"). Both render as a bordered (`outlineVariant`) block that,
  when selected/checked, fills solid with `primary` and switches its label to
  `onPrimary`, using `Modifier.selectable`/`Modifier.toggleable` rather than
  `FilterChip`'s pill shape. The label is always `FontWeight.Bold` (selected
  and unselected alike) rather than only bolding on selection —
  `ToggleChip` sits before a `weight(1f)` `Spacer` in `ArticleListPane`'s
  header row, so a selection-only weight change would shift the icons after
  it by a few px each time it's toggled; keeping the weight constant avoids
  that jitter, and the solid fill + `onPrimary` contrast already reads as
  clearly selected on its own. Reach for these, not `FilterChip`, for any new
  chip-like selection/toggle UI.
- **`FlatSwitch` / `FlatCheckbox`** (`ui/common/FlatToggles.kt`): flat replacements
  for M3's `Switch`/`Checkbox`, built on plain `Modifier.toggleable` (no ripple) with
  the same tokens as `SegmentedControl` (hairline `outlineVariant` border, `primary`
  fill when on, `onPrimary` content). `FlatSwitch` is a pill track with a snapping
  thumb (no slide animation, matching the app's immediate on/off convention);
  `FlatCheckbox` is a rounded square that fills `primary` + shows a `Check` when
  checked. Don't use M3's `Switch`/`Checkbox` directly at a call site. Count badges
  overlaid on an icon (e.g. the notification bell in `ArticleListPane`) likewise use a
  plain `Box`/`Text` pill (`error` fill, `onError` text) instead of M3's
  `BadgedBox`/`Badge`.
- **`KeryxTextField`** (`ui/common/KeryxTextField.kt`, expect/actual): the
  replacement for M3's `OutlinedTextField` for every text input — a flat,
  thin-bordered field on desktop, M3 on a future Android `actual`. See the Text
  input dialogs section above; don't use `OutlinedTextField`/`BasicTextField`
  directly at a call site.
- **Icon set — chrome vs. semantic state**: action/chrome icons (add, refresh,
  cloud sync, settings, folder/tag management, search, notifications, sort,
  mark-all-read, mark-unread, open-in-browser, back, close) use the
  `KeryxIcons.XOutlined` (or bare-name, single-variant) entry. Icons that
  encode persistent state rather than an action — `Star`/`StarBorder`,
  `Folder`, `ErrorFilled`, `PublicFilled`, `Article` — use the `XFilled` entry,
  since they're meant to read as "on/set" indicators, not as clickable chrome.
  Follow this split for any new icon: ask "is this a button, or a status
  marker?" Assets are Tabler Icons (MIT) svgs bundled under
  `composeResources/drawable/`, referenced only through `ui/common/KeryxIcons.kt`
  — never add a raw `painterResource(Res.drawable.ic_*)` call at a UI call
  site. Tabler (thin stroke, rounded terminals) was picked over Material
  Design's stock glyphs for a closer-to-macOS feel; it is not literal SF
  Symbols, since Apple's license restricts those to Apple-platform apps and
  this app also ships Windows/Linux builds. **When remapping `KeryxIcons` to a
  different icon set later** (e.g. an Android `actual` using Material icons,
  or any other re-skin), don't just match each icon by semantic name — grep
  for `graphicsLayer`/`rotate`/`scaleX`/`scaleY` modifiers applied around each
  `KeryxIcon(...)` call site first. A handful of icons are transformed at
  their call site to represent state with a single asset (e.g.
  `ArticleListPane.kt`'s sort button, which flips `KeryxIcons.Sort` vertically
  for ascending/descending) — swapping in a same-named but
  differently-shaped glyph (e.g. a symmetric double-arrow instead of a
  directional bars+arrow icon) silently breaks the transform without
  breaking compilation or tests, which is exactly what happened when Tabler's
  `arrows-sort` initially replaced the directional Material "sort" glyph.
  Where practical, prefer swapping between two distinct icon assets for a
  two-state icon (as the folder/tag expand chevron already does, picking
  between `ExpandMore`/`ChevronRight`) over transforming one shared asset —
  it's immune to this class of mistake by construction.
- **Flat surface pattern**: `NotificationCenterSheet`, `SetupScreen`'s
  `OptionCard`, and `TooltipIconButton`'s tooltip all use the same look for a
  "raised" panel instead of M3's tonal-elevation `Card`:
  `Surface(shape = MaterialTheme.shapes.<small|medium>, color =
  MaterialTheme.colorScheme.surfaceContainerLow (or surfaceContainerHighest
  for the tooltip), border = BorderStroke(1.dp,
  MaterialTheme.colorScheme.outlineVariant), tonalElevation = 0.dp)` — a flat
  fill plus a hairline border reads as native chrome; M3's default tonal
  elevation (mixing primary into the surface color to fake a shadow) doesn't.
  `AlertDialog` usages follow the same spirit even though they keep the
  scrim: pass `containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
  tonalElevation = 0.dp` (no border needed — the scrim already separates it).
  `Snackbar` similarly gets `containerColor =
  MaterialTheme.colorScheme.surfaceContainerHighest` instead of M3's default
  `inverseSurface`, so it doesn't look like a different color system.
- **Icon grouping (`ToolbarIconGroup`)**: related toolbar icons (e.g. add feed/refresh/cloud sync,
  search/notifications, sort/mark-all-read, star/mark-unread, copy-url/open-in-browser) are clustered
  into a rounded capsule via `ToolbarIconGroup` (`ui/common/TooltipIconButton.kt`), separated from other
  clusters in the same row by an 8dp `Spacer`. It renders with the same "flat surface pattern" tokens as
  everything else here (`surfaceContainerHighest` fill + `outlineVariant` 1.dp border, `tonalElevation =
  0.dp`) — this is a stand-in for a native grouped-toolbar look (e.g. macOS's glass/blur toolbar
  clustering), adopted because Compose Multiplatform has no native glass/blur primitive. When this app
  gets a native SwiftUI UI (iOS/iPadOS/macOS, see `external-spec.md`), replace `ToolbarIconGroup`'s
  usages with SwiftUI's native toolbar grouping (glass effect included) rather than trying to fake glass
  in Compose. Only wrap icons in `ToolbarIconGroup` where the cluster always has 2+ icons — a single icon
  in a capsule reads as visual noise, so lone icons (e.g. the settings icon) stay bare.
- **Other native-migration candidates**: besides `ToolbarIconGroup`, these are Compose-side hand-rolled
  approximations of something with a genuine native macOS/SwiftUI equivalent — worth swapping for the
  real thing during a future SwiftUI port rather than porting the Compose approximation as-is:
  - `ResizableDivider` (`ui/home/ResizableDivider.kt`) — hand-built pane divider with manual
    hover/cursor/drag handling → native `NSSplitView`/SwiftUI `NavigationSplitView`/`HSplitView` divider.
  - `WindowChrome.titleBarInsetDp` (`platform/WindowChrome.kt`) — manual inset math to dodge the
    traffic-light buttons → disappears entirely with a native full-size-content-view + unified toolbar.
  - The inline article search — `ArticleListPane`'s search `KeryxTextField` bound to
    `HomeViewModel.searchQuery` (results render reactively in the article list;
    `SearchResults.kt`'s `CenteredHint` covers the too-short-query / no-results states) →
    SwiftUI's `.searchable()`.
  - `selectionBackground()` (`ui/home/HomeCommon.kt`) row highlight in `ArticleListPane`/`FeedListPane` —
    hand-computed focused/unfocused-pane dimming → native `List` row selection already dims the same way.
  - `SettingsScreen`'s `SwitchRow` — now uses `FlatSwitch` (`ui/common/FlatToggles.kt`), consistent with
    the app's other flat controls → SwiftUI's native `Toggle` on a future SwiftUI port.
  - The drag-and-drop insertion-line system in `FeedListDragAndDrop.kt` (`InsertionLine`, `DropBoundary`,
    `RowHalf`, `resolveHalf`) — hand-computed row-half hit-testing and a manually drawn insertion line
    (explicitly modeled on macOS Notes' reorder UI) → SwiftUI `List`'s native `.onMove`/`.onInsert`
    reordering, which draws insertion indicators and row-shift animation for free.
  - `homeKeyboardShortcuts` (`ui/home/KeyboardNav.kt`) — an `onPreviewKeyEvent` key trap for app
    shortcuts (⌘/Ctrl+F, J/K, U, S, arrow-key pane nav) that's invisible from outside the app → SwiftUI's
    menu-bar `Commands`/`.keyboardShortcut()`, which register real, discoverable menu items with standard
    key-equivalent conflict resolution.
  - `Snackbar`/`SnackbarHost` (OPML import/export results, URL-copied toast) — weaker candidate than the
    others since SwiftUI has no 1:1 Snackbar equivalent; a SwiftUI port would need a bespoke transient
    banner view rather than a drop-in native replacement.
  - The Settings dialog's tab switcher (`KeryxDialogTabBar` in `ui/common/KeryxDialogs.kt`, used by
    `KeryxTabDialog`) — a flat Compose-drawn icon-over-label tab row, deliberately *not* styled to
    mimic macOS's native toolbar/segmented-control chrome → a native NSToolbar-style preferences tab
    switcher on a future SwiftUI port. Two rounds of AWT/Swing interop (`SwingPanel` +
    `JToggleButton`s with Aqua `JButton.buttonType` client properties) were tried and dropped before
    landing on the Compose version: `"segmented"` reads as a cramped joined pill unsuited to this
    layout, and `"toolbarItem"` (the semantically correct type — Apple's own docs describe it as "a
    button that displays an icon with a label underneath ... intended for use on the window frame")
    doesn't reliably indicate a `JToggleButton`'s selected state under Aqua, a known, still-open JDK
    bug (JDK-8250953). Don't re-attempt native Aqua chrome for this control — treat it the same as
    `ToolbarIconGroup`/`ResizableDivider`/etc. above, a SwiftUI-port target, not a
    Compose-Swing-interop target.
- **Icon hover feedback**: `TooltipIconButton` shows a subtle circular highlight on hover
  (`MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)`, no animation, same immediate on/off
  convention as `FlatIndication`/`ResizableDivider`), using `hoverable` on the same
  `MutableInteractionSource` passed to its `clickable`. Don't add a separate hover mechanism per call
  site — extend `TooltipIconButton` itself if the feel needs to change everywhere.
- **Popup vs. Dialog**: non-modal, anchored info panels (no scrim, dismiss on
  outside click, positioned relative to the control that opened them) use
  `androidx.compose.ui.window.Popup` — `NotificationCenterSheet`, opened from
  `ArticleListPane`'s bell icon, is the first example (a `Box` around the
  `TooltipIconButton` holds local `showNotifications` state and anchors the
  `Popup` with `alignment = Alignment.TopEnd` + a small `y` offset). The tag
  color picker is the second: `TagColorPickerPopup`
  (`ui/home/TagColorPicker.kt`), anchored to the tag row's own color dot, which
  is clickable at all times and independent of whether that row is being
  renamed — picking a swatch applies immediately, so there is nothing to
  confirm and nothing to block the window for. Note the container and the
  swatches are deliberately separate composables, so a phone-width
  `ModalBottomSheet` could host the same swatches later. Anything
  that demands full attention and blocks the rest of the UI (confirmations,
  text-prompt forms, the add-feed flow) stays an `AlertDialog`/`Dialog`
  — see `TextPromptDialog`, the various `AlertDialog` usages
  in `FeedListDialogs.kt`. (Search is neither — it's an inline field in
  `ArticleListPane`, see the Flat surface / migration notes above.) Don't reach for `Popup` for anything that should block
  interaction with the rest of the window, and don't reach for `Dialog` for
  something that's meant to feel like a lightweight, dismissable overlay.
- **Nothing Compose-drawn can appear over the article detail pane's content area**: the article
  reader (`ArticleDetailPane.kt`) is a heavyweight native `SwingPanel` WebView, and a heavyweight
  AWT surface always composites above lightweight Compose content in the same window (the same
  limitation `KeryxDialogs.kt` documents for why dialogs are real `DialogWindow`s, not `Popup`).
  The reader is composed unconditionally for the pane's whole lifetime — never behind an `if` —
  because mounting/unmounting it (or moving its bounds) makes Compose Desktop's
  `SwingInteropContainer` revalidate and repaint the *entire window*, not just this pane (see
  `docs/known-issues.md`, "Selecting an article after none was selected flickered the whole
  window"). Consequently, empty/error states for this pane (no article selected, no content) are
  rendered as HTML *inside* the WebView (`ui/article/ArticleWebViewHtml.kt`), not as Compose
  `Text`, and the toolbar above it keeps the exact same Compose structure (same buttons, only
  `enabled` toggles) across every state rather than conditionally hiding an action — hiding one
  would change the row's child count, and while that happens not to move this particular row's
  height today (all its children are fixed-size icons), don't rely on that; keep the structure
  literally unconditional. Do not reintroduce an early return that skips composing the WebView.
