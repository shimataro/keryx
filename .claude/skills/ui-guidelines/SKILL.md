---
name: ui-guidelines
description: Keryx UI/Compose style guidelines. Read when adding or modifying Compose under `ui/` (home / common / settings / setup / article) or `platform/NativeMenu`. Defines pane tonal roles, divider policy, article card style, layout stability, platform-native components (buttons / toggles / text fields / list rows / overflow menus / theme), dialog/popup conventions, and icon usage rules.
---

# UI Guidelines (Compose Multiplatform)

Style conventions for Keryx's Compose UI. Follow these when adding or modifying
UI anywhere under `ui/` — the Home screen's 3-pane layout and list rows
(`ui/home/`), the shared controls (`ui/common/`), settings (`ui/settings/`),
setup (`ui/setup/`), the article reader (`ui/article/`), and the native
overflow menu (`platform/NativeMenu`).

**The governing principle is "each platform follows its own native UI idiom"** — see
`external-spec.md` §9. Desktop (macOS, and Windows/Linux sharing its look for the Java/Swing
reasons documented there) gets a flat, hairline-bordered, non-ripple aesthetic; Android gets
Material 3's own shapes, ripple, and components; iOS will eventually get native SwiftUI. Most of
this document was originally written when Keryx was desktop-only, so **most rules below describe
the desktop `actual` specifically**, not a shared app-wide style — where a rule doesn't already say
"on Android" or "the Android `actual`", assume it's a desktop-only convention and check the
component's own `expect`/`actual` split (grep the component name) before assuming the same rule
applies verbatim on Android. Do not read "flat", "no ripple", or "hairline border" anywhere below
as a target for Android; those phrases describe desktop's own choice, not a default the Android
`actual` is deviating from.

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
- **A reserved-but-empty slot still costs layout space — for something purely
  decorative that never needs to report its own size or receive input, draw
  it instead of laying it out.** Real incident: the feed list's drag
  insertion marker used to be a `Box` reserving 2dp of height as a sibling
  inside the row, on or off. That slot's space belonged entirely to whichever
  row's layout contained it, so the *visual* gap between two rows (their own
  margins plus the reserved slot) could only ever split unevenly, never
  at its true midpoint — a click just past one row's highlight, closer to it
  than to its neighbor, could still resolve to the neighbor. Fixed by
  `insertionMarkers` (`ui/home/FeedListDragAndDrop.kt`), a draw-only
  modifier that paints directly into the row's own existing margin — no
  layout slot, on or off, so the fix in the previous bullet's spirit
  (reserve unconditionally) does not apply here: the *right* fix for a
  draw-only element is to not lay it out at all.

## Pane structure & tonal roles

The 3 panes (`FeedListPane` / `ArticleListPane` / `ArticleDetailPane`) do not
share a common `TopAppBar`. Each action icon lives in the pane it operates on,
not in global chrome:

- Feed-management icons (add feed / refresh all / cloud sync) — top of
  `FeedListPane`
- Settings — reached through the native application menu bar (macOS
  Preferences…/AppMenuBar/KDE Global Menu — see `MenuCommand.OpenSettings`).
  On platforms with no such menu (Android — `platform/PlatformOs.kt`'s
  `hasNativeAppMenu == false`), `FeedListToolbarRow` grows its own settings
  icon button at the top of `FeedListPane` instead, sending the same command;
  About gets the equivalent treatment as an `ActionLinkRow` at the bottom of
  `GeneralTab`
- Search is layout-dependent rather than a fixed icon: at `PaneLayout.Triple`
  the real, editable field lives in `FeedListPane` (unchanged from before this
  section's rewrite); at a narrow layout it folds into a read-only
  `KeryxCollapsedSearchBar` there instead, and the real field moves into
  `ArticleListPane`'s `SearchListPane` (a `KeryxExpandedSearchBar`, alongside
  the results it filters — see "Adaptive pane layout & touch affordances"
  below for why). Outside the Search scope at a narrow layout,
  `ArticleListPane`'s own header row (`ArticleListTopBar`) also gets a search
  icon (`onSearchClick`) as its entry point, in the position (before
  notifications/sort/mark all read) this bullet used to describe as fixed.
- Sort / mark all read — header row of `ArticleListPane`, unchanged at every
  layout.
- Notifications (the bell) — normally the same `ArticleListPane` header row,
  but it is the one action that follows the *user* rather than a pane: an alert
  entry point that only exists on one of three screens is not reachable from
  the app's own launch destination. `FeedListPane` therefore grows its own bell
  exactly when the article list is not on screen beside it (only
  `PaneLayout.Single`'s depth 1 today), driven from `HomeScreen`'s
  `visiblePanes` result — `notifVm.takeIf { HomePane.ArticleList !in visible }`
  — so the two panes can never both draw one, or both skip it. It is a single
  icon and therefore stays bare (no `ToolbarIconGroup` capsule), separated from
  the add/refresh/sync cluster by the standard 8dp and placed ahead of it, the
  same relative position it holds in `ArticleListTopBar`. `ArticleDetailPane`
  deliberately has none at any layout — a reading screen carries no
  notification entry point, matching how Android's own apps treat a detail
  destination.

Panes are tinted left-to-right with increasingly bright Material3 tonal
surface roles, so boundaries read from tone alone rather than requiring a
hard line: `surfaceContainerLow` (`FeedListPane`) → `surfaceContainer`
(`ArticleListPane`) → `surface` (`ArticleDetailPane`). See
[KeryxTheme.kt](../../../composeApp/src/commonMain/kotlin/works/merc/keryx/app/ui/theme/KeryxTheme.kt)
for the base color scheme and each pane file for where the tone is applied.

The macOS traffic-light inset (`WindowChrome.titleBarInsetDp`) is applied
only to the pane that sits in the window's top-left corner — currently
`FeedListPane`'s header row.

## Adaptive pane layout & touch affordances

`ui/home/HomePaneLayout.kt`'s `paneLayoutFor` resolves how many of the 3 panes fit side by side at
the current width (`PaneLayout.Triple`/`Dual`/`Single`), derived from the same per-pane minimum
widths this file already uses for tonal roles — not an independent breakpoint. Desktop always
resolves `Triple` (`WINDOW_MIN_WIDTH >= TRIPLE_PANE_MIN_WIDTH`); narrower widths (phones) resolve
`Single`, showing one pane at a time as a hierarchical stack with its own back button
(`ArticleListPane`/`ArticleDetailPane`'s `onNavigateUp`) and — on Android — the OS back
gesture/button (`platform/BackHandler`). Nothing about the panes' own internal layout (tonal
roles, dividers, row chrome) changes between layouts; only how many are mounted at once does —
with two deliberate exceptions, both about an affordance that has nowhere else to live once the
panes become separate screens: the search field (see below) and the notification bell (see "Pane
structure & tonal roles" above).
`ui/home/HomePaneLayout.kt`'s `canNavigateBack(layout, depth)` is the single predicate for
"does going back one step actually change anything on screen" — `false` at `PaneLayout.Triple`
(nothing ever changes there) and at `PaneLayout.Dual` depth 1→2 (the sliding window shows the
same two panes at both depths, see `visiblePanes`' own KDoc) — both `HomeScreen`'s own
`BackHandler` and `ArticleListPane`'s `navigateUpEnabled` are driven by it, so a back press that
would produce no visible change is never silently swallowed.

**Search at a narrow layout.** The search field itself moves, not just its surrounding chrome: at
`PaneLayout.Triple` it stays the plain, always-editable `KeryxTextField` this app has always had,
in `FeedListPane`'s own sidebar (results render reactively in `ArticleListPane`'s `SearchListPane`
beside it). At `PaneLayout.Single` there is no second pane to show those results in at all; at
`PaneLayout.Dual` there is one, but the article list is the pane a narrow layout always keeps on
screen (`visiblePanes` includes it at every `Dual` depth), and the field has to live in exactly one
place, never as two editable copies bound to the same `HomeViewModel.searchQuery`. So both narrow
layouts put the field on the results pane instead, which also leaves it where it is across a
`Single`↔`Dual` rotation — `FeedListPane` folds its copy into a read-only
`KeryxCollapsedSearchBar` (`ui/common/KeryxSearchBar.kt`; tapping it selects
`ArticleFilter.Search` and advances the navigation stack, it never itself takes focus or a
keystroke), and the real, editable field becomes `SearchListPane`'s own header
(`KeryxExpandedSearchBar`), sitting directly above the results it filters. Outside the Search
scope at a narrow layout, `ArticleListTopBar`'s own `onSearchClick` gives `ArticleListPane` a
matching entry point (a search icon, ahead of notifications/sort/mark-all-read) — otherwise a user
who lands on the article list first (the narrow layout's own default) would have no way in.

This split is driven by the same nullable-callback idiom the rest of this file already uses for
"is this pane narrow": `FeedListPane`'s `onSelectionAdvance` and `ArticleListPane`'s `onNavigateUp`
are `null` at `PaneLayout.Triple` and non-null otherwise — **not** a `PaneLayout` or
`isTouchPrimary` parameter passed down. `isTouchPrimary` in particular would be wrong here: a
touch-primary Android device in landscape at a tablet width can still resolve `PaneLayout.Triple`
(the same threshold desktop uses), where the field must stay in `FeedListPane` exactly as it does
on desktop.

`HomeViewModel.pendingSearchFocus` is a latched `StateFlow<Boolean>`, not a one-shot
`SharedFlow` — the request to focus the field (tapping the collapsed bar, the sidebar's own
"Search" row, or Cmd+F/the menu bar's "Search…") is raised in the very click that advances the
navigation stack, so the pane that will actually own the field has not composed yet when the
request fires; a `SharedFlow` with no subscriber yet would drop it silently, which is exactly what
used to make Android's search feel broken. The latch stays set until whichever field composes
next consumes it (`consumeSearchFocusRequest()`), and `HomeViewModel.selectFilter` clears an
unconsumed one when the user navigates elsewhere first.

**Touch input on the feed list.** A mouse can drag a draggable row (a folder header, or a feed row
inside a folder group — tag rows and tag-nested feed copies were never drag sources) from anywhere
on it, because a click and a drag-start are already unambiguous with a precise pointer. Touch has
no such distinction — a press-and-move could equally mean "reorder this row" or "scroll the list" —
so on a touch-primary platform (`platform/PlatformOs.kt`'s `isTouchPrimary`), dragging only starts
from a dedicated trailing handle (`ui/home/FeedListRowParts.kt`'s `DragHandle`, a fixed ≡-dot icon;
gating logic in `ui/home/FeedListDragGestures.kt`'s `feedListReorderDrag`). Everywhere else on the
row falls through to the `LazyColumn`'s own scroll gesture untouched. Long-press for the row's
context menu (`nativeContextMenu`) and drag-from-handle for reordering are deliberately different
gestures on the same row — see `platform/NativeMenu.android.kt`'s KDoc for how the two coexist
without one stealing the other's press.

**Touch density.** Each pane's own click-to-focus background (a mouse-only affordance — see
`ui/home/HomeCommon.kt`'s `paneActivation`) and every interactive list row's minimum height
(`ui/home/ListRowChrome.kt`'s `listRowMinHeight`, matching M3's `NavigationDrawerItem` minimum —
`56.dp` on a touch-primary platform, `0.dp`/no floor elsewhere) are both gated the same way as the
drag handle above: a plain `isTouchPrimary` check, applied via `Modifier.heightIn(min = ...)`
*before* a row's own content padding and immediately after `listRowSurface` — M3's 56dp is an
*outer* height (content plus the row's own padding), so flooring the content alone instead would
add the padding on top and leave each row's floored height depending on how much padding it has.
Never touching `LIST_ROW_VERTICAL_MARGIN` (the drag insertion marker's geometry depends on it —
see the Divider policy section above), and never applied outside `listRowSurface` either, or the
floor would swallow that margin instead of the highlight it's meant for — see
`listRowMinHeight`'s own KDoc. A row's individual touch-only elements (the tag color dot, the
folder/tag expand chevron) grow their own click target to a full 48dp the same way, independent of
their drawn/visible size.

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
  Separate rows with the selection-highlight background (`selectionBackground`)
  instead. The highlight is a rounded rectangle inset by
  `LIST_ROW_HORIZONTAL_MARGIN` / `LIST_ROW_VERTICAL_MARGIN`, while the
  **clickable/drag band is the row's whole reported bounds** — full width,
  margin included, no outer-margin dead strip and no unclickable wedge under
  the rounded corners — so every list row is a **single composable with a single
  modifier chain**: `FeedRow`/`FolderGroupHeader` no longer need a wrapping
  `Column` to lay out their drag insertion marker as a sibling `Box`, now that
  the marker is painted rather than laid out (see `insertionMarkers`
  below). This used to be the other way around (`clip`/`background` before
  the interactive modifiers, so hit-testing matched the rounded inset) until
  that was found to leave real dead zones — the outer margin and the four
  rounded corners — permanently unclickable, and for the old `Column`-wrapped
  rows, worse: any point outside a padded descendant's own bounds, even
  squarely inside the wrapping `Column`'s reported bounds (confirmed
  empirically — see `listRowClickable`'s KDoc). Use the two helpers in
  [ListRowChrome.kt](../../../composeApp/src/commonMain/kotlin/works/merc/keryx/app/ui/home/ListRowChrome.kt)
  instead of hand-rolling the chain:
  - `Modifier.fillMaxWidth().listRowClickable(interactionSource, onClick)` —
    the click/drag hit area, applied with **nothing** before it in the chain
    (no padding, no clip) so it covers the row's entire reported bounds.
    Passes `indication = null` deliberately; press feedback is `listRowSurface`'s
    job, confined to the inset highlight.
  - `.nativeContextMenu(...)`, then — for a feed-list row that can be a drag
    insertion boundary — `.insertionMarkers(top, bottom)`
    (`ui/home/FeedListDragAndDrop.kt`), then
    `.listRowSurface(background, kind, interactionSource, decoration)` — `kind`
    is a `ListRowKind` (`NavItem` for feed/folder/tag rows, `ListItem` for
    article rows) that only matters on Android; see "Platform-native list
    rows" below for what each `expect`/`actual` does with it. On desktop this
    is still the row's standard `LIST_ROW_HORIZONTAL_MARGIN`/
    `LIST_ROW_VERTICAL_MARGIN` outer margin, `MaterialTheme.shapes.small`
    clip, `background`, an optional `decoration` (e.g. a drop-target border),
    then the shared `interactionSource`'s flat press feedback via
    `Modifier.indication` — then the row's own inner content padding.
    `insertionMarkers` must sit *before* `listRowSurface`: it paints into the
    margin `listRowSurface`'s leading `padding` reserves, which applying it
    afterwards would inset it away from, and whose `decoration` slot is
    clipped to the rounded rect
    regardless.
  - For `ArticleRow`, the `.heightIn(min = rowHeight)` call must stay *after*
    the inner content padding (see Article card style below).

  **The space between two rows is expressed in two chosen values —
  `LIST_ROW_GUIDE_THICKNESS` (2dp) and `LIST_ROW_GUIDE_CLEARANCE` (1dp) — from
  which `LIST_ROW_VERTICAL_MARGIN` is *derived*, not chosen:**

  ```kotlin
  LIST_ROW_VERTICAL_MARGIN = LIST_ROW_GUIDE_CLEARANCE + LIST_ROW_GUIDE_THICKNESS / 2f  // 2dp
  ```

  It is exactly what one row has to give up to hold its half of the guide line
  plus its clearance from it, so changing either chosen value carries the margin
  with it. (Declaration order matters: Kotlin initializes a file's top-level
  properties in order, so the derived one must come last or it silently reads
  0dp.) Three things follow at once, so change any of them only deliberately:

  - the **visible gap** between two rows is twice the margin (4dp), stacked as
    clearance + guide + clearance; with no guide drawn, all of it is pane color
    between the two highlights;
  - the **hit boundary** is that gap's midpoint — which is also the guide line's
    centre — because each row's `clickable` covers its own band *including* its
    own margin, so a click anywhere in the gap (clearance or guide) selects the
    **nearer** row and no strip ever selects nothing;
  - the **drag insertion marker** is half the guide thickness per side, so the
    two rows touching a boundary together make one 2dp line centred on the very
    boundary the click resolves against, while each keeps its highlight one
    clearance clear of it.

  `insertionMarkers` therefore takes its thickness from
  `LIST_ROW_GUIDE_THICKNESS` rather than carrying its own, paints into the
  *outer* part of the margin (the clearance is the inner part), and draws with
  `drawWithContent` (after the content) so nothing the row paints can hide it.
  Both rows touching a boundary paint their own side, which makes the line a
  literal picture of where a click will go: its upper half selects the row above,
  its lower half the row below. Note it deliberately does **not** fill the gap —
  a marker flush against both highlights is what the clearance exists to prevent.

  There is no always-visible rule between rows: the guide line appears only
  while a drag is looking for a drop position. Row separation at rest is the
  selection highlight and the gap, per the bullets above.

  Clicking *precisely* on a highlight's edge still tends to select the
  neighbour. That is macOS's own behaviour and shrinking the margin does not fix
  it (it was tried down to zero) — see
  [known-issues.md](../../../docs/known-issues.md) for the measurements and
  everything ruled out, rather than re-investigating it.

## Platform-native list rows

`listRowSurface` (see above) is `expect`/`actual` and takes a `ListRowKind` — `NavItem` for
feed/folder/tag rows, `ListItem` for article rows — because the two platforms don't just differ in
color/shape here, they follow genuinely different native row idioms:

- **Desktop**: one look regardless of `kind` — the inset, rounded-rectangle highlight described
  throughout the Divider policy section above. Desktop has no equivalent split between "nav item"
  and "content list item" chrome, so the desktop `actual` ignores `kind` entirely.
- **Android**: `NavItem` keeps the same inset (`LIST_ROW_HORIZONTAL_MARGIN`/`LIST_ROW_VERTICAL_MARGIN`
  are unchanged — the drag insertion marker's geometry, per the Divider policy section above, depends
  on the vertical one specifically) but clips to a full pill (`CircleShape`) instead of a lightly
  rounded rectangle, matching M3's `NavigationDrawerItem`. `ListItem` is full-bleed — no horizontal
  inset, no corner clip — matching M3's plain `ListItem`; article rows are never a drag target, so
  nothing depends on the exact vertical spacing there the way `NavItem`'s does.

**When adding a new list row**, decide which `ListRowKind` it is by asking the same question M3
asks: does this row represent a navigation/filter target (a feed, folder, tag — something you tap to
change what's showing), or a content item in a list (an article — something you tap to open)? Pass
`kind` explicitly; it has no default (see `listRowSurface`'s own KDoc for why — a forgotten `kind`
should be a compile error, not a silently wrong Android row style).

`selectionBackground`/`selectionContentColorOrNull` (`ui/home/HomeCommon.kt`) — the color functions
list rows pass into `listRowSurface`'s `background` parameter — additionally read
`LocalRowSelectionVisible`, a `CompositionLocal` `HomeScreen` sets to `false` at `PaneLayout.Single`
(see "Adaptive pane layout & touch affordances" above): on a phone-width screen, tapping a row
navigates *away* from it (drills into the article list or the article detail), so a lingering
highlight there would mark a row the user can no longer see, unlike at `Dual`/`Triple` where the
selected row's pane stays on screen alongside whichever pane it opened. This is desktop-and-Android
shared logic (desktop is unaffected — it never resolves `Single`), not a per-platform `actual`.

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

**Desktop only.** `FeedListPane`'s sticky "フォルダー"/"タグ" headers keep this
behavior at every `PaneLayout` (see "Adaptive pane layout & touch
affordances" below) — narrowing the window doesn't change how that list
scrolls, only how many *other* panes are visible alongside it. iOS is still
only planned (see `external-spec.md` §2); defer whether/how sticky section
headers apply there until that platform's own layout is designed, the same
way `app-architecture.md` defers the Android icon-set question.

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
  select the right-clicked row. On Android, `onOpen` is intentionally ignored:
  a long-press only opens the menu and never selects the row. Keep any side
  effects inside `onOpen` desktop-only (e.g. row selection), not required for
  the action to work on Android. An **empty** `items` list shows no menu and
  makes `onOpen` the only effect on desktop — that's how a pane background
  moves focus on right-click without selecting anything. On Android, an empty
  `items` list also shows no menu and simply consumes the long-press gesture.
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
The Android `actual` goes back to M3's own `OutlinedTextField`/`TextField` (Material
is the desirable look there — see the `KeryxTextField` bullet under "Native-feel
restyle" below for the full "why fight the platform" reasoning). Don't reach
for `OutlinedTextField`/`TextField`/`BasicTextField` directly at a call site — use
`KeryxTextField`. Its `modifier` param lands on the inner text field, so a
`focusRequester` / `onFocusChanged { it.isFocused }` on it behaves as it did on
`OutlinedTextField`.

- **Validation**: a `blockingError: (String) -> String?` callback returns a
  message that blocks confirm (e.g. duplicate tag name); a separate
  `infoHint: (String) -> String?` returns a non-blocking hint (e.g. "leaving
  this blank resets to the default title"). Either feeds `isError` /
  `supportingText` on `KeryxTextField`; both return `null` when there's
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

## Native-feel restyle (per-platform press feedback, icon set, popovers)

The app does not embed AWT/Swing widgets via `SwingPanel` for ordinary controls
(e.g. `Switch`/dropdowns) — JetBrains Compose Multiplatform has unresolved
z-order/overdraw/crash bugs for `SwingPanel` inside scrollable containers, and
every candidate control here lives inside one (`SettingsScreen`'s
`verticalScroll` `Column`, `FeedListPane`/`ArticleListPane`'s `LazyColumn`).
This whole constraint — and everything under it about Swing interop — is desktop-only; Android has
no AWT/Swing layer at all.

Three native-widget exceptions exist on desktop, all outside scrollable containers, and
**none of them is a pattern to extend**:

- `platform/NativeMenu.kt` — shows a menu on demand rather than embedding a
  persistent Compose-tree node (see above).
- `ui/common/KeryxDialogs.desktop.kt` — the dialog button row (`JButton`s in a
  `SwingPanel`), so confirm/cancel get the platform's real buttons.
- `ui/home/ArticleDetailPane.kt` — the reader `WebView`, a heavyweight AWT
  panel wrapping an OS browser view. This is also *why* every dialog is a
  separate `DialogWindow`: a heavyweight panel always paints over in-window
  Compose `Popup`s.

For everything else, each platform's native *feel* comes entirely from Compose-side
theme/shape/indication/icon choices — desktop's flat/hairline-border/no-ripple aesthetic on one
side, Android's own Material 3 ripple/shapes/components on the other:

- **Press feedback and shapes — `ui/theme/PlatformTheme.kt`** (`expect`/`actual`): the single
  seam that switches the whole app's interaction feel and corner-radius scale at once. Desktop's
  `actual` provides a custom `IndicationNodeFactory` (`FlatIndication`) via
  `CompositionLocalProvider(LocalIndication provides ...)` — an immediate, non-animated
  `onSurface`-ish low-alpha rectangle overlay while pressed, no ripple spread/fade — plus
  `platformShapes`, a tighter corner-radius scale than M3's default. Android's `actual` provides
  neither override: leaving `LocalIndication`/`LocalRippleConfiguration`/`platformShapes` at their
  M3 defaults is exactly what gives every `clickable` and M3 component (`Button`/`IconButton`/
  `Switch`/`Checkbox`/…) its own real ripple and M3's own shape scale — a real ripple is what
  "native" means on Android, the same way flat, non-animated press feedback is what "native" means
  on desktop. `KeryxTheme.kt` (`commonMain`) composes `ProvidePlatformInteraction` around
  `MaterialTheme(shapes = platformShapes, …)`; **don't add per-call-site `indication = ripple(...)`
  overrides on desktop** — if a desktop control needs a different feel, change `FlatIndication`
  itself in `PlatformTheme.desktop.kt` so the app stays consistent there. `LocalIndication` only
  affects plain `Modifier.clickable`/`selectable`/`toggleable` call sites — M3 components hardcode
  `ripple()` internally and never consult it, which is why desktop's `PlatformTheme.desktop.kt`
  *also* provides `CompositionLocalProvider(LocalRippleConfiguration provides ...)` with every
  `RippleAlpha` channel zeroed, as a global safety net for those (there's no public API to disable
  the ripple *animation* itself, but zero alpha makes it invisible); Android's `actual` skips this
  entirely, since a visible M3 ripple on those components is exactly the point there. For buttons
  specifically, prefer
  `ui/common/FlatButtons.kt`'s `FlatButton` (primary/filled — `primary` fill),
  `FlatTonalButton` (secondary — `secondaryContainer` fill + hairline
  `outlineVariant` border, for actions that still need clear button affordance
  like OPML import/export, Dropbox disconnect, update check, setup cards), and
  `FlatTextButton` (bare, inline) over M3's `Button`/`FilledTonalButton`/
  `TextButton` at a **`commonMain` call site** — they're `expect`/`actual`
  (like `KeryxTextField`/`KeryxDialogs` below), and the desktop `actual`s are
  built on plain `Modifier.clickable` so they pick up `FlatIndication`
  directly rather than relying on the `RippleConfiguration` fallback. A solid
  fill (not a transparent outline) is what makes a secondary action read as a
  tactile button rather than a link, so there is intentionally no transparent
  "outlined" flat button. The Android `actual`s delegate straight to M3's own
  `Button`/`FilledTonalButton`/`TextButton` — see the `KeryxTextField`
  bullet below for why that's the right call on that platform.
- **`SegmentedControl<T>` / `ToggleChip`**
  (`ui/common/SegmentedControl.kt`, expect/actual): the replacement for
  Material3's `FilterChip` for both "pick one of N" (`SegmentedControl`, used
  by `SettingsScreen`'s theme/font-size/cache/timeout/refresh-interval rows)
  and standalone boolean toggles (`ToggleChip`, used by `ArticleListPane`'s
  "unread only"), **at a `commonMain` call site** — reach for these, not
  `FilterChip`, there. The desktop `actual`s render as a bordered
  (`outlineVariant`) block that, when selected/checked, fills solid with
  `primary` and switches its label to `onPrimary`, using
  `Modifier.selectable`/`Modifier.toggleable` rather than `FilterChip`'s pill
  shape. The label is always `FontWeight.Bold` (selected and unselected
  alike) rather than only bolding on selection — `ToggleChip` sits before a
  `weight(1f)` `Spacer` in `ArticleListPane`'s header row, so a
  selection-only weight change would shift the icons after it by a few px
  each time it's toggled; keeping the weight constant avoids that jitter, and
  the solid fill + `onPrimary` contrast already reads as clearly selected on
  its own. The Android `actual`s delegate to M3's own
  `SingleChoiceSegmentedButtonRow`+`SegmentedButton` and `FilterChip` — see
  the `KeryxTextField` bullet below for why.
- **`FlatSwitch` / `FlatCheckbox`** (`ui/common/FlatToggles.kt`, expect/actual):
  the replacement for M3's `Switch`/`Checkbox` **at a `commonMain` call site** —
  don't call M3's `Switch`/`Checkbox` directly there. The desktop `actual`s are
  built on plain `Modifier.toggleable` (no ripple) with the same tokens as
  `SegmentedControl` (hairline `outlineVariant` border, `primary` fill when on,
  `onPrimary` content): `FlatSwitch` is a pill track with a snapping thumb (no
  slide animation, matching the app's immediate on/off convention);
  `FlatCheckbox` is a rounded square that fills `primary` + shows a `Check`
  when checked. The Android `actual`s delegate to M3's own `Switch`/`Checkbox`
  — see the `KeryxTextField` bullet below for why that's the right call on
  that platform. `KeryxBadgedIcon` (`ui/common/KeryxBadge.kt`, expect/actual) —
  the count badge overlaid on an icon (currently only the notification bell,
  in `ArticleListPane` and — at a single-pane width — `FeedListPane`) — follows
  the same split: desktop's `actual` draws a
  hand-rolled `Box`/`Text` pill (`error` fill, `onError` text), Android's
  `actual` uses M3's own `BadgedBox`/`Badge`. Don't add a raw `Box`/`Text` pill
  or a raw `BadgedBox`/`Badge` at a `commonMain` call site — use
  `KeryxBadgedIcon`.
- **`KeryxTextField`** (`ui/common/KeryxTextField.kt`, expect/actual): the
  replacement for M3's `OutlinedTextField` for every text input — a flat,
  thin-bordered field on desktop, plain M3 on Android (same "why fight the
  platform" reasoning `KeryxDialogs`/`KeryxIcons`/`FlatButtons`/
  `FlatToggles`/`SegmentedControl`'s Android `actual`s follow: Android's own
  users expect M3's native look, touch-target sizing, and accessibility
  behavior for standard controls, where desktop's macOS-leaning flat
  aesthetic is the one that's out of place). See the Text input dialogs
  section above; don't use `OutlinedTextField`/`BasicTextField` directly at a
  call site.
- **Icon set — chrome vs. semantic state**: action/chrome icons (add, refresh,
  cloud sync, settings, folder/tag management, search, notifications, sort,
  mark-all-read, mark-unread, open-in-browser, back, close) use the
  `KeryxIcons.XOutlined` (or bare-name, single-variant) entry. Icons that
  encode persistent state rather than an action — `Star`/`StarBorder`,
  `Folder`, `ErrorFilled`, `PublicFilled`, `Article` — use the `XFilled` entry,
  since they're meant to read as "on/set" indicators, not as clickable chrome.
  Follow this split for any new icon: ask "is this a button, or a status
  marker?" `KeryxIcons` (`ui/common/KeryxIcons.kt`) is `expect`/`actual`:
  the desktop `actual` bundles Tabler Icons (MIT) svgs (thin stroke, rounded
  terminals — picked over Material Design's stock glyphs for a
  closer-to-macOS feel; not literal SF Symbols, since Apple's license
  restricts those to Apple-platform apps and this app also ships
  Windows/Linux builds), while the Android `actual` bundles Material Symbols
  Outlined (Apache-2.0), matching Android's own native visual language. Both
  live under their own source set's `composeResources/drawable/` and are
  referenced only through `ui/common/KeryxIcons.kt` — never add a raw
  `painterResource(Res.drawable.ic_*)` call at a UI call site.
  **When remapping either `actual` to a different icon set later**
  (or adding a new one), don't just match each icon by semantic name — grep
  for `graphicsLayer`/`rotate`/`scaleX`/`scaleY` modifiers applied around each
  `KeryxIcon(...)` call site first. A handful of icons are transformed at
  their call site to represent state with a single asset — swapping in a
  same-named but differently-shaped glyph (e.g. a symmetric double-arrow
  instead of a directional bars+arrow icon) silently breaks the transform
  without breaking compilation or tests, which is exactly what happened when
  Tabler's `arrows-sort` initially replaced the directional Material "sort"
  glyph. Where practical, prefer swapping
  between two distinct icon assets for a
  two-state icon (as the folder/tag expand chevron already does, picking
  between `ExpandMore`/`ChevronRight`) over transforming one shared asset —
  it's immune to this class of mistake by construction.
  **`ArticleListPane.kt`'s sort button is the case that proves the rule.** It
  used to flip a single `KeryxIcons.Sort` vertically for
  ascending/descending, and this document previously recorded that as
  "confirmed still safe" on Android because Material Symbols' `sort` is the
  same three-bar shape. That reasoning was wrong: desktop's Tabler
  `sort-descending` conveys direction through the **arrow** beside its bars
  (whose own lengths are 9/7/7, not a staircase), and Material Symbols' `sort`
  has no arrow at all, so the flip was effectively invisible on Android —
  where, with no menu bar and a tooltip that only appears on long-press,
  nothing else showed the current order either. It now uses
  `KeryxIcons.SortDescending`/`SortAscending`, while retaining the selected directional glyph
  when the button is disabled (the search
  scope). Desktop's two directional entries are Tabler's own
  `sort-descending`/`sort-ascending`; Android's are **local composites** —
  Material Symbols ships no directional sort glyph
  (google/material-design-icons#189), so `ic_sort_{ascending,descending}_material.xml`
  place the stock `sort` and `arrow_downward`/`arrow_upward` paths side by side
  via `<group>` transforms rather than redrawing either by hand. **On both
  platforms the bar staircase reverses along with the arrow**, so direction is
  carried twice over: desktop gets that for free (Tabler ships `sort-descending`
  and `sort-ascending` as two glyphs, 9/7/7 vs 7/7/9), and the Android
  ascending composite mirrors the stock `sort` bars vertically to match.
  Three things about those two files are load-bearing and easy to undo by
  accident, so each carries its own comment saying so: both groups take the
  **same uniform scale** (scaling only one, or scaling either non-uniformly,
  leaves the bars and the arrow's shaft at different stroke weights inside one
  glyph); the source paths are the **wght 700** variants, not the default
  weight — at 125 units thick instead of 80, they land at roughly the 2px of
  the default-weight icons beside them in the toolbar once scaled down to fit
  two glyphs in one 24dp box; and the ascending file's bars are that mirror,
  which reads as a swap of the outer two bar widths because the three bar bands
  are symmetric about y 480.5. Diffing either file's path data against the
  stock default-weight glyph will therefore show differences on all three
  counts — none of them is a defect to "correct". They are also the repo's only
  `<group>`-using vector assets; Compose Multiplatform's own `XmlVectorParser`
  supports the full `pivot`/`scale`/`translate`/`rotation` set on every target. `KeryxIcons.ArrowBack`
  is the other concrete instance of this fix: `ChevronRight` used to double as
  a flipped (`scaleX = -1f`) "back" icon at three call sites
  (`ArticleListPane`/`ArticleDetailPane`'s back buttons,
  `NativeMenu.android.kt`'s submenu-back row) on top of its real job as the
  unflipped tree-expand chevron. `ArrowBack` gives "back" its own dedicated
  entry (Android = Material's `arrow_back`, desktop = Tabler's `arrow-left`),
  so `ChevronRight` now means only one thing and no call site transforms it.
- **`KeryxSettingRow`** (`ui/common/KeryxSettingRow.kt`, expect/actual): the replacement for
  `SettingsComponents.kt`'s `LinkRow`/`ActionLinkRow`/`SwitchRow` (now thin wrappers around it) **at
  a `commonMain` call site** — don't hand-roll a hover-styled `Text` row there. Desktop's `actual`
  reproduces the former exact look: primary-colored text with underline + hand cursor on hover
  (`onClick` given, no `trailing`), or a plain-colored label beside a `trailing` control with only
  that control interactive (`SwitchRow`'s case) — hover has no touch equivalent, so this is the one
  `KeryxSettingRow` shape Android's `actual` doesn't reproduce. Android's `actual` is a real M3
  `ListItem`, whose own tap target covers the *whole row* when `onClick` is given (including the
  `SwitchRow` case — Android's `ListItem` doesn't distinguish "trailing control only"), and
  `supporting` (a hover tooltip on desktop) renders as `ListItem`'s own `supportingContent` line.
- **`KeryxAnchoredPanel`** (`ui/common/KeryxAnchoredPanel.kt`, expect/actual): the replacement for a
  raw `androidx.compose.ui.window.Popup` at a `commonMain` call site — see "Popup vs. Dialog" below
  for when a Popup (vs. a Dialog) is the right choice in the first place; this is what backs that
  choice on each platform once it is. Desktop's `actual` is the former `Popup` call, unchanged.
  Android's `actual` is a real M3 `ModalBottomSheet` — besides matching Android's own idiom for a
  lightweight overlay, this is *necessary* there: `NotificationCenterSheet`/`TagColorPickerPopup`
  can be opened while an article is showing, and Android's `WebView` (embedded via `AndroidView`,
  the platform's own approximation of desktop's heavyweight-AWT z-order problem — see "Nothing
  Compose-drawn can appear..." below) composites above a bare `Popup` the same way desktop's WebView
  does above a bare Compose overlay; `ModalBottomSheet`'s own dedicated window layer avoids this.
  Content passed to `KeryxAnchoredPanel` should have **no `KeryxRaisedSurface`/shadow/width
  wrapping of its own** — desktop's bare `Popup` supplies no container (the content must still
  provide the flat-surface wrapping itself there), but `ModalBottomSheet` already supplies one, so
  callers gate their own surface wrapping behind `isTouchPrimary` (see `NotificationCenterSheet`/
  `TagColorPickerPopup` for the pattern).
- **`KeryxPaneTopBar`** (`ui/common/KeryxPaneTopBar.kt`, expect/actual): the replacement for a
  hand-rolled `navigationIcon`/title/trailing-`actions` `Row` at a `commonMain` call site — see
  `FeedListToolbarRow`, `ArticleListTopBar`'s back+title row, and `ArticleDetailToolbar`, all now
  built on this. This does **not** create a shared top bar across the 3 panes (see "Pane structure
  & tonal roles" above) — each pane still calls it separately with its own `actions`. Desktop's
  `actual` is a plain `Row` reproducing each former call site's exact layout; because the three
  differed in padding, `KeryxPaneTopBar` applies none of its own — a caller supplies padding (and
  keeps `WindowDragArea`/`WindowChrome.titleBarInsetDp` wrapped *around* the call, since neither is
  shared across all three panes either) via its own `modifier`. Android's `actual` is a real M3
  `TopAppBar`.
- **`KeryxCollapsedSearchBar`/`KeryxExpandedSearchBar`** (`ui/common/KeryxSearchBar.kt`,
  expect/actual): the narrow-layout search pair described in "Adaptive pane layout & touch
  affordances" above — a read-only entry point and an editable search-screen header,
  respectively. **Deliberately not built on `KeryxPaneTopBar`**: an editable field's own minimum
  height (56dp) grows past `TopAppBar`'s fixed 64dp container once the font-size setting scales it
  up (to 1.4×), clipping it — a plain pill shape has no fixed height to clip against. Android's
  `actual` matches M3's own search-bar tokens (`SearchBarDefaults.inputFieldShape`/
  `InputFieldHeight`/`TonalElevation`/`ShadowElevation`) for both, and uses
  `SearchBarDefaults.InputField` itself (not a self-drawn `BasicTextField`) for the editable one —
  so the two read as one continuous surface expanding, the way search does in Gmail/Drive/Photos.
  The collapsed one's `Modifier.clickable(onClickLabel = …, role = Role.Button)` is explicit rather
  than an M3 `Surface(onClick = …)`, which sets neither (see "Accessibility" below). Desktop's
  `actual` never renders in production (desktop always resolves `PaneLayout.Triple`, where
  `FeedListPane` keeps its original editable field instead), and exists only so `desktopTest` can
  render and assert this composable directly.
- **Raised surfaces — `KeryxRaisedSurface`** (`ui/common/KeryxSurface.kt`, expect/actual): the
  container `NotificationCenterSheet`, `SetupScreen`'s `OptionCard`, `TagColorPicker`'s popup, and
  `SettingsCard` all use for a "raised" panel instead of M3's tonal-elevation `Card` **at a
  `commonMain` call site** — don't build a raw `Surface(...)` with these tokens by hand there.
  Desktop's `actual` is the flat surface pattern: `Surface(shape = shape, color =
  MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp,
  MaterialTheme.colorScheme.outlineVariant), tonalElevation = 0.dp)` — a flat fill plus a hairline
  border reads as native chrome there; M3's default tonal elevation (mixing primary into the
  surface color to fake a shadow) doesn't. Android's `actual` instead uses a distinctly-tinted
  tonal container tier (`MaterialTheme.colorScheme.surfaceContainerHigh`), no border — M3's own
  elevation idiom is a tonal-container color step, not a hairline border, and a bordered card reads
  as desktop chrome there. `KeryxAlertDialog` follows the same split for its own `containerColor`/
  `tonalElevation` parameters: desktop's `actual` honors whatever the caller passes (typically
  `surfaceContainerLow` / `0.dp`, matching the flat pattern — no border needed there since the scrim
  already separates the dialog), but Android's `actual` **ignores both** and lets `AlertDialogDefaults`'
  own values apply, for the same reason `KeryxRaisedSurface` does.
- **Icon grouping — `ToolbarIconGroup`** (`ui/common/TooltipIconButton.kt`, expect/actual): related
  toolbar icons (e.g. add feed/refresh/cloud sync, search/notifications, sort/mark-all-read,
  star/mark-unread, copy-url/open-in-browser) are clustered via `ToolbarIconGroup`, separated from
  other clusters in the same row by an 8dp `Spacer`. Desktop's `actual` renders the cluster as a
  rounded capsule with the flat surface pattern's tokens (`surfaceContainerHighest` fill +
  `outlineVariant` 1.dp border, `tonalElevation = 0.dp`) — a stand-in for a native grouped-toolbar
  look (e.g. macOS's glass/blur toolbar clustering), adopted because Compose Multiplatform has no
  native glass/blur primitive; when this app gets a native SwiftUI UI (iOS/iPadOS/macOS, see
  `external-spec.md`), replace that usage with SwiftUI's native toolbar grouping (glass effect
  included) rather than trying to fake glass in Compose. **Android's `actual` renders no capsule at
  all** — a plain, unadorned `Row` — since M3's own toolbars don't wrap their icons in a container;
  wrapping one there would itself be the non-native choice. Only wrap icons in `ToolbarIconGroup`
  where the cluster always has 2+ icons — a single icon in a desktop capsule reads as visual noise,
  so lone icons (e.g. the settings icon) stay bare (this consideration doesn't apply to Android,
  which never draws a capsule regardless of cluster size).
- **Other native-migration candidates**: besides desktop's `ToolbarIconGroup` capsule, these are Compose-side hand-rolled
  approximations of something with a genuine native macOS/SwiftUI equivalent — worth swapping for the
  real thing during a future SwiftUI port rather than porting the Compose approximation as-is:
  - `ResizableDivider` (`ui/home/ResizableDivider.kt`) — hand-built pane divider with manual
    hover/cursor/drag handling → native `NSSplitView`/SwiftUI `NavigationSplitView`/`HSplitView` divider.
    Already `isTouchPrimary`-aware (not just a SwiftUI-port target): on Android, where a tablet-width
    landscape viewport can reach `PaneLayout.Triple` the same as desktop, it renders as a plain static
    divider with no hover/drag affordances at all — M3 has no touch-oriented pane-splitter idiom, and
    8dp is well under any usable touch target. Pane widths stay at whatever `local_settings` last
    recorded there.
  - `WindowChrome.titleBarInsetDp` (`platform/WindowChrome.kt`) — manual inset math to dodge the
    traffic-light buttons → disappears entirely with a native full-size-content-view + unified toolbar.
  - Article search — at `PaneLayout.Triple`, `FeedListPane`'s search `KeryxTextField` bound to
    `HomeViewModel.searchQuery` (results render reactively in `ArticleListPane`'s `SearchListPane`;
    `SearchResults.kt`'s `CenteredHint` covers the too-short-query / no-results states); at a
    narrow layout, `KeryxCollapsedSearchBar`/`KeryxExpandedSearchBar` (`ui/common/KeryxSearchBar.kt`)
    instead — see "Adaptive pane layout & touch affordances" below → either way, SwiftUI's
    `.searchable()`.
  - `selectionBackground()` (`ui/home/HomeCommon.kt`) row highlight in `ArticleListPane`/`FeedListPane` —
    hand-computed focused/unfocused-pane dimming → native `List` row selection already dims the same way.
  - `SettingsScreen`'s `SwitchRow` — now uses `FlatSwitch` (`ui/common/FlatToggles.kt`), consistent with
    the app's other flat controls → SwiftUI's native `Toggle` on a future SwiftUI port.
  - The drag-and-drop insertion-marker system in `FeedListDragAndDrop.kt` (`insertionMarkers`,
    `DropBoundary`, `RowHalf`, `resolveRowHalf`) — hand-computed row-half hit-testing and a manually
    drawn insertion line (explicitly modeled on macOS Notes' reorder UI) → SwiftUI `List`'s native `.onMove`/`.onInsert`
    reordering, which draws insertion indicators and row-shift animation for free.
  - `homeKeyboardShortcuts` (`ui/home/KeyboardNav.kt`) — an `onPreviewKeyEvent` key trap for app
    shortcuts (⌘/Ctrl+F, J/K, U, S, arrow-key pane nav) that's invisible from outside the app → SwiftUI's
    menu-bar `Commands`/`.keyboardShortcut()`, which register real, discoverable menu items with standard
    key-equivalent conflict resolution.
  - `Snackbar`/`SnackbarHost` (OPML import/export results) — weaker candidate than the
    others since SwiftUI has no 1:1 Snackbar equivalent; a SwiftUI port would need a bespoke transient
    banner view rather than a drop-in native replacement. (The URL-copied feedback is no longer purely
    an inline-icon affair — see `LocalSnackbarHostState`'s own KDoc: Android now also reports it via a
    real M3 `Snackbar`, below API 33 only, where the OS itself doesn't already show a clipboard-copy
    confirmation. Android's second use is `HomeScreen`'s `ForegroundAlertSnackbar`, which announces a
    warning/error the moment it is raised — the bell's badge alone only reaches a user already looking
    at the pane hosting it, and these alerts are raised asynchronously by the startup tasks and the
    background worker with no OS notification behind them; see `docs/error-design.md`. Desktop still
    has none of either, per this app's no-in-app-snackbar convention.)
  - **Desktop only.** The Settings dialog's tab switcher (desktop's `KeryxTabDialog`
    actual in `KeryxDialogs.desktop.kt`) now uses Material3's
    `SecondaryScrollableTabRow`/`Tab` via the shared `KeryxDialogTabs` helper, making
    it a standard Compose Multiplatform component just like Android's `KeryxTabDialog`
    actual uses `PrimaryScrollableTabRow`/`Tab` — only the surrounding dialog shape
    and tab-row variant differ by platform. The previous hand-rolled flat
    `KeryxDialogTabBar` was removed. On a future SwiftUI port, replace this with a
    native NSToolbar-style preferences tab switcher rather than porting the Compose
    approximation as-is. Two earlier rounds of AWT/Swing interop (`SwingPanel` +
    `JToggleButton`s with Aqua `JButton.buttonType` client properties) were abandoned:
    `"segmented"` read as a cramped joined pill and `"toolbarItem"` didn't reliably
    indicate selected state under Aqua (JDK-8250953). Treat the current M3 tab row the
    same as `ToolbarIconGroup`/`ResizableDivider`/etc. above: a SwiftUI-port target,
    not a Compose-Swing-interop target.
- **`TooltipIconButton` and its tooltip trigger** (`ui/common/TooltipIconButton.kt`, expect/actual):
  every icon button with a tooltip goes through this **at a `commonMain` call site** — don't
  hand-roll `IconButton` + `TooltipBox` there. Desktop's `actual` re-implements M3's `IconButton`
  with plain `Modifier`s so pressing it uses `FlatIndication` (see "Press feedback and shapes"
  above) instead of M3's hardcoded ripple, and shows a subtle circular highlight on hover
  (`MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)`, no animation, same immediate on/off
  convention as `FlatIndication`/`ResizableDivider`), using `hoverable` on the same
  `MutableInteractionSource` passed to its `clickable`. Android's `actual` is a plain M3
  `IconButton` (a real ripple, no hover mechanism — hover has no touch equivalent) inside the same
  `TooltipBox` desktop uses; `TooltipBox` already triggers its tooltip on long-press on a
  touch-primary platform — matching Android's own tooltip convention — with no extra gesture code
  needed on either `actual`'s part. Don't add a separate hover mechanism per call
  site — extend the desktop `actual` itself if that platform's feel needs to change everywhere.
  Its tooltip content is `FlatTooltipContent` (also `ui/common/TooltipIconButton.kt`,
  expect/actual — shared with `LinkRow` and the feed-gone indicator's own `TooltipBox` calls, which
  are otherwise untouched by this split): desktop's `actual` is the flat surface pattern (see
  "Raised surfaces" above, `surfaceContainerHighest` variant); Android's `actual` is M3's own
  `PlainTooltip`.
- **Popup vs. Dialog**: non-modal, anchored info panels (no scrim, dismiss on
  outside click, positioned relative to the control that opened them) use
  `KeryxAnchoredPanel` (see above) — `NotificationCenterSheet`, opened from
  whichever pane currently hosts the bell (see "Pane structure & tonal roles"
  above), is the first example (a `Box` around the
  `TooltipIconButton` holds local `showNotifications` state and anchors the
  panel with `alignment = Alignment.TopEnd` + a small `anchorOffsetY`, desktop-only
  positioning — see `KeryxAnchoredPanel`'s own KDoc). The tag
  color picker is the second: `TagColorPickerPopup`
  (`ui/home/TagColorPicker.kt`), anchored to the tag row's own color dot, which
  is clickable at all times and independent of whether that row is being
  renamed — picking a swatch applies immediately, so there is nothing to
  confirm and nothing to block the window for. Note the container and the
  swatches are deliberately separate composables, so both share the same
  swatches with no changes needed there. Both render as a real M3
  `ModalBottomSheet` on Android (`KeryxAnchoredPanel`'s Android `actual`) —
  matching that platform's own idiom for this kind of lightweight overlay, and
  necessary for `NotificationCenterSheet` specifically (see `KeryxAnchoredPanel`'s
  own entry above for why a bare `Popup` there would end up behind the article
  reader's `WebView` on Android). Anything
  that demands full attention and blocks the rest of the UI (confirmations,
  text-prompt forms, the add-feed flow) stays an `AlertDialog`/`Dialog`
  — see `TextPromptDialog`, the various `AlertDialog` usages
  in `FeedListDialogs.kt`. (Search is neither a Popup nor a Dialog — nor, on
  Android, M3's own `ExpandedFullScreenSearchBar` dialog, which was
  considered and deliberately not used: it can't host the search results,
  which is exactly the problem the narrow-layout redesign exists to fix. It
  is instead a plain step of the existing 3-pane navigation stack — an
  editable field at `PaneLayout.Triple` (`FeedListPane`), or, at a narrow
  layout, a `KeryxCollapsedSearchBar` entry point plus a
  `KeryxExpandedSearchBar` header on `ArticleListPane`'s `SearchListPane`
  alongside the results — see "Adaptive pane layout & touch affordances"
  below.) Don't reach for `KeryxAnchoredPanel` for anything that should block
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
  **Android's `WebView` (embedded via `AndroidView`) has the same limitation**: it composites above
  ordinary Compose content in the same Activity window, so a plain `Scaffold(snackbarHost = ...)`
  drawn as regular Compose content is invisible whenever an article is open. `HomeScreen.kt`'s
  Snackbar host works around this by rendering through a `Popup` instead (which attaches its own
  window-level layer, above the WebView) rather than `Scaffold`'s own slot — see
  `LocalSnackbarHostState`'s own KDoc. Any future Android-side "float something over the current
  pane" UI needs the same `Popup`/`KeryxAnchoredPanel`-style treatment, not a plain Compose overlay.

## Accessibility

Conventions established piecemeal across the codebase (each currently only documented on its own
KDoc) — follow these for any new `expect`/`actual` control or list-row interaction under `ui/`:

- **Give a new interactive `expect`/`actual` control a `Role`.** A row/control that merely
  `clickable`s conveys nothing about *what kind* of control it is to a screen reader. Use
  `Modifier.selectable(selected, role = ...)` for a row that participates in a mutually-exclusive
  selection (see `ui/home/ListRowChrome.kt`'s `listRowClickable`, backing every feed/folder/tag/
  article row) and `Modifier.toggleable(value, role = Role.Switch)` for a row whose click flips a
  boolean the row itself represents (see `ui/common/KeryxSettingRow.android.kt`'s toggle-row path).
  When a control has its own inner interactive child (e.g. a trailing `FlatSwitch` inside a
  toggle row), wrap that child's container in `Modifier.clearAndSetSemantics {}` so its semantics
  don't merge in as a second, separately-focusable stop for what is visually one control.
- **A purely decorative icon gets `contentDescription = null`, with the label on the clickable
  parent's `onClickLabel` instead** — never on both, or a screen reader announces the label twice
  (once for the icon, once for the click action). `ui/home/HomeCommon.kt`'s
  `ExpandCollapseChevron` is the reference implementation; its own KDoc explains why.
- **Route state-description text through `ui/i18n/AccessibilityDescriptions.kt`** (e.g.
  `checkedStateDescription()`/`uncheckedStateDescription()`) rather than inlining a literal at the
  call site — this is also where constraint #3's "no hardcoded user-facing strings" applies to
  accessibility-only text nobody sees on screen. `platform/NativeMenu.android.kt`'s checkable menu
  items are the reference call site (`Modifier.semantics { this.stateDescription = ... }`).
- **A pointer-only gesture (drag-to-reorder, long-press context menu) needs a
  `CustomAccessibilityAction` equivalent**, since a screen reader user cannot perform either. See
  `ui/home/FeedListRowParts.kt`'s `reorderAccessibilityActions` (a "move up"/"move down" action
  pair, driven by the exact same `HomeViewModel` mutation a completed drag would trigger) for the
  pattern: only expose an action for a direction that is actually available (`onMoveUp`/
  `onMoveDown` is `null` when already first/last in scope), and gate the whole modifier on the
  same `isTouchPrimary` condition the underlying gesture itself is gated on.
