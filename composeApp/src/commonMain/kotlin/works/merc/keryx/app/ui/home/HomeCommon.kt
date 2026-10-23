package works.merc.keryx.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.platform.NativeMenuShortcut
import works.merc.keryx.app.platform.isMacOs
import works.merc.keryx.app.presentation.home.FeedListRowSelection
import works.merc.keryx.app.presentation.home.FeedListSelectionTarget
import works.merc.keryx.app.presentation.home.canOpenInBrowser
import works.merc.keryx.app.presentation.home.renameHomeKey
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.home_collapse
import works.merc.keryx.app.resources.home_expand
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons

/**
 * The Compose [Key] for the bare rename shortcut, derived from the shared [renameHomeKey] (the
 * single source of truth that `homeShortcutFor` actually fires the rename on) by reverse-mapping
 * it through [toHomeKey] — so the native application menu's `FeedRename` accelerator and
 * [renameNativeShortcut] can never drift from what the keyboard handler in `KeyboardNav.kt` does.
 * [isMacOs] is overridable for tests only; production call sites use the platform default.
 */
internal fun renameKey(isMacOs: Boolean = works.merc.keryx.app.platform.isMacOs): Key {
    val target = renameHomeKey(isMacOs)
    return renameKeyCandidates.firstOrNull { it.toHomeKey() == target }
        ?: error("No Compose Key maps to rename HomeKey $target")
}

/** Compose keys [renameKey] may resolve to; must cover every value [renameHomeKey] can return. */
private val renameKeyCandidates = listOf(Key.Enter, Key.F2)

/**
 * The bare-key context-menu shortcut for rename/edit-type actions (feed/folder/tag), matching each
 * OS's own file-manager rename convention (Explorer/Nautilus/Dolphin use F2, Finder uses Return).
 * Renders as a real native accelerator on Linux; AWT's `MenuShortcut` can't represent a bare key at
 * all, so macOS/Windows show no hint for it — see `NativeMenuShortcut`'s doc comment.
 */
internal val renameNativeShortcut = NativeMenuShortcut(renameKey())

/**
 * The bare-key context-menu shortcut for unsubscribe/delete-type actions (feed/folder/tag). Same
 * platform-display caveat as [renameNativeShortcut].
 */
internal val deleteNativeShortcut = NativeMenuShortcut(Key.Delete)

/**
 * Whether a row's selection highlight should be visibly painted at all — see [LocalRowSelectionVisible].
 */
internal val LocalRowSelectionVisible = staticCompositionLocalOf { true }

/**
 * Whether a hardware key has been pressed at least once this session — `HomeScreen` latches this
 * `true` on the first `KeyDown` `homeKeyboardShortcuts` observes (see `KeyboardNav.kt`'s
 * `onKeyboardEngaged`) and never resets it. Gates [listRowOutline]'s keyboard-focus ring: a
 * touch-only session (a phone, or a tablet with no keyboard ever attached) should never show a
 * focus indicator meant for someone navigating by keyboard, matching the web's own
 * `:focus-visible` convention of only drawing a focus ring once keyboard interaction is evidenced.
 */
internal val LocalKeyboardEngaged = staticCompositionLocalOf { false }

/**
 * The [SnackbarHostState] backing the `SnackbarHost` `HomeScreen` renders — inside a `Popup`, not
 * `Scaffold`'s own slot, so it draws above the article reader's native WebView (see that call
 * site's own comment). `null` on desktop, which per the `ui-guidelines` skill has no in-app
 * snackbar convention (its previous transient toasts were replaced by inline expressions — see
 * that skill's "Native-feel restyle" section). `null` is also the value in any preview/test
 * composition that never provides one. A composable that wants to show a snackbar should treat a
 * `null` value here as "do nothing" rather than crash.
 *
 * Note neither of `HomeScreen`'s own snackbars goes through this `CompositionLocal`: the foreground
 * alert Snackbar is composed outside the provider (alongside the host itself), and the URL-copied
 * one is raised by [ArticleUrlCopier], the shared copy handler `HomeScreen` creates — both take the
 * state directly, so the copied snackbar appears for every copy route, including when no pane
 * reading this local (the reader, say) is on screen.
 */
internal val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState?> { null }

/**
 * The one handler behind every route to "Open in browser" (an article's URL: the reader's button,
 * the menu bar, the keyboard shortcut, the article row's context menu) and "Open site" (a feed's
 * site URL: the feed row's context menu, the menu bar): opens [url] only when [canOpenInBrowser]
 * allows it, so a non-http(s) link from the feed is never handed to the OS, whichever route asked.
 * The routes' enablement uses the same predicate.
 */
internal fun openInBrowserIfAllowed(url: String?) {
    url?.takeIf(::canOpenInBrowser)?.let(BrowserOpener::open)
}

/**
 * Click-to-focus for a pane's background — on a mouse+keyboard platform there is no OS-level
 * click-to-focus for the panes inside this one window, so a plain click anywhere in a pane's
 * empty background is this app's only way to move keyboard focus onto it.
 *
 * Dropped entirely on a touch-primary platform ([isTouchPrimary]): every interactive control a
 * touch pane actually contains (a row, a button) already calls its own `onActivated` when tapped
 * (see each pane's own call sites), so a *dedicated* empty-background click handler moves focus
 * nothing else already moves — this is unrelated to whether keyboard focus itself exists on
 * Android (it does; a physical keyboard can be attached to a tablet and arrow keys still move it —
 * see `ListRowChrome.kt`'s `PaneFocusIndication.Ring`). Keeping this modifier there anyway
 * would only add an unlabeled, full-size accessibility click node sitting behind every other
 * control in the pane.
 *
 * Deliberately a bare tap detector rather than [Modifier.clickable], which would otherwise be the
 * obvious choice here: on a mouse platform `clickable` grabs real Compose focus for *itself* on the
 * pointer-down (`isRequestFocusOnClickEnabled()` is `true` on desktop), and it does so on the
 * `Main` pass — i.e. one step *after* the deepest node under the cursor has already handled that
 * same down. Where the pane's content is a `SelectionContainer` (the Compose fallback article
 * reader, `ui/article/ArticleContentView.kt`), that ordering silently breaks text selection: the
 * container starts a selection on the down and takes focus for itself, this modifier's `clickable`
 * immediately takes it back, and `SelectionManager`'s own focus-loss handler releases the
 * just-started selection — so a click-and-drag selects nothing at all and only the pane's own
 * bookkeeping runs. A tap detector wants no focus of its own, which is exactly right here anyway:
 * the pane's `onActivated` deliberately sends Compose focus to the *screen root* (see
 * `HomeScreen`'s `activatePane`/`returnKeyboardFocusToRoot`), never to the pane background. It also
 * drops the unlabeled full-size accessibility click node `clickable` would add, for the same reason
 * the touch branch above avoids one. Cancellation semantics are unchanged: a consumed down (a row,
 * a button) or a consumed drag (a text selection) never reaches [onActivated], just as
 * `clickable`'s own click would not have.
 *
 * @param isTouchPrimary Overridable for tests only (mirrors `feedListReorderDrag`'s own
 *   `isTouchPrimary` parameter) — production call sites always use the platform default from
 *   `platform/PlatformOs.kt`.
 */
@Composable
internal fun Modifier.paneActivation(
    onActivated: () -> Unit,
    isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary,
): Modifier = if (isTouchPrimary) {
    this
} else {
    val activate = rememberUpdatedState(onActivated)
    this.pointerInput(Unit) { detectTapGestures { activate.value() } }
}

/**
 * The expand/collapse chevron used by [TagRow] and `FolderGroupHeader` — a two-asset toggle
 * (never a single flipped/rotated asset, per the `ui-guidelines` skill's icon-set section) with an
 * `onClickLabel` for accessibility, since the icon's own `contentDescription` is `null` (the label
 * would otherwise be announced twice, once for the icon and once for the click action).
 *
 * The icon always measures [EXPAND_CHEVRON_SLOT] (`20.dp`) — its own drawn size — regardless of
 * touch density; this is also the horizontal step a row nested *under* a chevron-bearing row is
 * indented past (see `feedRowIndent` in `FeedListDragAndDrop.kt`), so keeping it a plain constant
 * is what keeps the feed list's hierarchy from depending on touch density at all. A touch-primary
 * platform still grows the *hit target* to M3's [TOUCH_TARGET_MIN_SIZE] (`48.dp`), via
 * [Modifier.layoutAs] — which reserves only the icon's own [EXPAND_CHEVRON_SLOT] of layout space
 * and centers the larger clickable box around it, so the row's own indentation columns never
 * widen just because this one control's hit area did. Unlike the tag color dot's own
 * margin-absorbing trick, there's no spare margin here to absorb, so this relies on the row's own
 * [listRowMinHeight] density pass to keep the row from being forced taller than its neighbors just
 * by this one control.
 *
 * @param isTouchPrimary Overridable for tests only (mirrors `feedListReorderDrag`'s own
 *   `isTouchPrimary` parameter) — production call sites always use the platform default.
 */
@Composable
internal fun ExpandCollapseChevron(
    expanded: Boolean,
    onToggle: () -> Unit,
    isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary,
) {
    val label = stringResource(if (expanded) Res.string.home_collapse else Res.string.home_expand)
    val icon = if (expanded) KeryxIcons.ExpandMore else KeryxIcons.ChevronRight
    if (isTouchPrimary) {
        Box(
            Modifier.layoutAs(EXPAND_CHEVRON_SLOT, EXPAND_CHEVRON_SLOT)
                .size(TOUCH_TARGET_MIN_SIZE)
                .clickable(onClickLabel = label, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            KeryxIcon(icon, contentDescription = null, modifier = Modifier.size(EXPAND_CHEVRON_SLOT))
        }
    } else {
        KeryxIcon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(EXPAND_CHEVRON_SLOT).clickable(onClickLabel = label, onClick = onToggle),
        )
    }
}

/** Shared tint for the starred-article indicator (article list row badge, detail-view toggle). */
val StarredColor = Color(0xFFFFC107)

/**
 * Whether the article list's "refresh this list" action — the pull-to-refresh gesture, its
 * accessibility action, and the hardware-keyboard shortcut — is available: only on a touch-primary
 * platform (a mouse has no pull gesture, and desktop's own app menu already owns the same
 * shortcut), never over search results (refreshing the feeds behind a result list isn't what the
 * gesture means there), and never with no feeds at all (nothing to refresh). The single rule both
 * `ArticleListPane` and `HomeScreen`'s keyboard handling read, so the two can never disagree. The
 * keyboard shortcut additionally requires the list to be on screen (`articleListOnScreen`): the
 * gesture and the accessibility action live on the list itself and so can't be reached while it is
 * hidden, but a key press reaches `HomeScreen` regardless.
 */
internal fun pullRefreshAvailable(isTouchPrimary: Boolean, searchActive: Boolean, hasNoFeeds: Boolean): Boolean =
    isTouchPrimary && !searchActive && !hasNoFeeds

/**
 * Which feed-list row currently has its name open for inline editing (`FeedListPane`'s single
 * edit-mode state — there is never more than one). Kept as an id + kind rather than as the row's
 * whole `Feeds`/`Folders`/`Tags` value, so a row that recomposes with fresh data mid-edit still
 * matches, and so the two id spaces (a feed and a folder can never be edited at once anyway) can't
 * be confused for each other.
 */
internal sealed interface InlineEditTarget {
    /** The exact rendered row this edit is on — a feed edits on its folder-group row unless
     * [Feed.tagId] names the specific tag-nested row it was started from instead. */
    val rowInstance: FeedListRowSelection

    /** The filter that selects this row, so the pane can scroll it into view before editing starts. */
    val filter: ArticleFilter get() = rowInstance.filter

    /** @param tagId Non-null when editing started on the row nested under that tag, rather than the
     *   feed's canonical folder-group row (see [FeedListRowSelection]). */
    data class Feed(val id: String, val tagId: String? = null) : InlineEditTarget {
        override val rowInstance: FeedListRowSelection
            get() = tagId?.let { FeedListRowSelection.FeedInTag(id, it) }
                ?: FeedListRowSelection.FeedInFolderGroup(id)
    }

    data class Folder(val id: String) : InlineEditTarget {
        override val rowInstance: FeedListRowSelection get() = FeedListRowSelection.Folder(id)
    }

    data class Tag(val id: String) : InlineEditTarget {
        override val rowInstance: FeedListRowSelection get() = FeedListRowSelection.Tag(id)
    }
}

/** The inline-edit target for a selection resolved by [resolveFeedListSelectionTarget] — the bridge
 * between "what is selected" (keyboard shortcut / menu command) and "what row is being edited".
 * [rowInstance] carries which rendered row instance (folder-group vs. a specific tag-nested copy)
 * the selection was actually made on, so a feed selected via its tag-nested row edits there too. */
internal fun FeedListSelectionTarget.toInlineEditTarget(rowInstance: FeedListRowSelection): InlineEditTarget = when (this) {
    is FeedListSelectionTarget.Feed -> InlineEditTarget.Feed(
        id = feed.id,
        tagId = (rowInstance as? FeedListRowSelection.FeedInTag)?.takeIf { it.feedId == feed.id }?.tagId,
    )
    is FeedListSelectionTarget.Folder -> InlineEditTarget.Folder(folder.id)
    is FeedListSelectionTarget.Tag -> InlineEditTarget.Tag(tag.id)
}

/**
 * Scrolls [index] into view: jumps directly there if it isn't currently rendered, otherwise nudges
 * just enough to bring it fully within the viewport when it's only partially visible at an edge.
 */
suspend fun LazyListState.scrollToIndexIfNeeded(index: Int) {
    val info = layoutInfo
    val itemInfo = info.visibleItemsInfo.find { it.index == index }

    if (itemInfo == null) {
        animateScrollToItem(index)
    } else {
        val viewportStart = info.viewportStartOffset
        val viewportEnd = info.viewportEndOffset
        val itemStart = itemInfo.offset
        val itemEnd = itemInfo.offset + itemInfo.size

        when {
            itemStart < viewportStart -> animateScrollBy((itemStart - viewportStart).toFloat())
            itemEnd > viewportEnd -> animateScrollBy((itemEnd - viewportEnd).toFloat())
        }
    }
}

/**
 * Auto-scroll speed for a drag hovering near a scrollable list's edge, in pixels per second.
 *
 * The sign matches [LazyListState.scrollBy]'s: negative scrolls back toward the start of the list
 * (pointer near [viewportTop]), positive scrolls on toward its end (pointer near [viewportBottom]).
 * Returns 0 in the dead zone between the two edge zones. Inside an edge zone the speed ramps
 * linearly from 0 at the zone's inner boundary ([edgeZonePx] in from the edge) to
 * [maxSpeedPxPerSec] at the edge itself, and stays at [maxSpeedPxPerSec] for a [pointerY] outside
 * the viewport entirely.
 *
 * A viewport shorter than `2 * edgeZonePx` shrinks both zones to half its height rather than
 * letting them overlap, so every position still resolves to exactly one zone (or the dead zone).
 */
fun autoScrollVelocityPxPerSec(
    pointerY: Float,
    viewportTop: Float,
    viewportBottom: Float,
    edgeZonePx: Float,
    maxSpeedPxPerSec: Float,
): Float {
    val height = viewportBottom - viewportTop
    if (height <= 0f || edgeZonePx <= 0f) return 0f
    val zone = edgeZonePx.coerceAtMost(height / 2f)
    val distanceFromTop = pointerY - viewportTop
    val distanceFromBottom = viewportBottom - pointerY
    return when {
        distanceFromTop < zone -> -maxSpeedPxPerSec * ((zone - distanceFromTop) / zone).coerceIn(0f, 1f)
        distanceFromBottom < zone -> maxSpeedPxPerSec * ((zone - distanceFromBottom) / zone).coerceIn(0f, 1f)
        else -> 0f
    }
}

