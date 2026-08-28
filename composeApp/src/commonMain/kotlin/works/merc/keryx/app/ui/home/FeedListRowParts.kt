package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.home_move_down
import works.merc.keryx.app.resources.home_move_up
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons

// Small leaf composables used to build the rows of FeedListPane (feed avatar + unread-count badge).

/**
 * Displays a feed avatar using its favicon when available, or the feed title's initial as a fallback.
 *
 * @param title The feed title used to create the fallback avatar.
 * @param faviconUrl The favicon URL, or `null` or blank when the title-based avatar should be displayed.
 */
@Composable
internal fun FeedAvatar(title: String, faviconUrl: String?) {
    if (faviconUrl.isNullOrBlank()) {
        LetterAvatar(title)
    } else {
        // The favicon must NOT sit in its own graphics layer: the feed row's
        // `Modifier.dragAndDropSource` records the row into a Picture and draws that snapshot,
        // and a nested layer's async update (the favicon finishing loading) doesn't invalidate
        // that recorder — so the favicon would stay invisible until a hover-driven redraw.
        // Hence rounded corners are clipped at the canvas level (drawWithCache + clipPath, which
        // creates no layer) instead of `Modifier.clip`, and Coil's own `clipToBounds` is disabled.
        Box(
            Modifier.size(18.dp).drawWithCache {
                val r = 4.dp.toPx()
                val path = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, r, r)) }
                onDrawWithContent { clipPath(path) { this@onDrawWithContent.drawContent() } }
            },
        ) {
            AsyncImage(
                model = faviconUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                error = painterResource(KeryxIcons.PublicFilled),
                clipToBounds = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Displays a circular avatar containing the first character of the title.
 *
 * @param title The title from which to derive the avatar character.
 */
@Composable
private fun LetterAvatar(title: String) {
    val letter = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        Modifier.size(18.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/**
 * Displays a count badge using a color appropriate for the current selection, focus, drag-source,
 * and drop-target state.
 *
 * @param count The count to display.
 * @param selected Whether the badge is selected.
 * @param focused Whether the badge is focused.
 * @param isDropTarget Whether the row is an active drag-and-drop target.
 * @param isDragSource Whether the row is the source of the currently dragged feed.
 * @param onContainerColor The `on<Container>` color to use while [isDropTarget], matching the
 * row's own drop-target container color (e.g. `onSecondaryContainer` for a folder, `onTertiaryContainer` for a tag).
 */
@Composable
internal fun CountBadge(
    count: Long,
    selected: Boolean,
    focused: Boolean,
    isDropTarget: Boolean = false,
    isDragSource: Boolean = false,
    onContainerColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Text(
        count.toString(),
        style = MaterialTheme.typography.labelSmall,
        color = dropTargetContentColorOrNull(isDropTarget, selected, focused, onContainerColor, isDragSource)
            ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A touch-only drag affordance appended to the end of a draggable row (a folder header, or a feed
 * row inside a folder group — tag-nested feed copies and tag rows themselves aren't drag sources,
 * see `FeedListDragController.sourceAt`). `ui/home/FeedListDragGestures.kt`'s
 * `feedListReorderDrag` gates a touch press's *start* position to this handle's band when
 * `isTouchPrimary`, so the rest of the row stays a plain scrollable surface instead of hijacking
 * every touch into a drag attempt (mouse users keep the old "drag from anywhere on the row"
 * convention, since a precise pointer doesn't have this ambiguity with scrolling). Call sites
 * render this only when `isTouchPrimary`; it draws no gesture handling of its own.
 */
@Composable
internal fun DragHandle() {
    KeryxIcon(
        KeryxIcons.DragHandle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp).size(20.dp),
    )
}

/**
 * The assistive-technology counterpart of [DragHandle], applied to the whole row band rather than
 * to the handle icon (which is purely decorative, and whose drag is raw pointer input —
 * `feedListReorderDrag` — that a screen reader cannot perform at all): a "move up" / "move down"
 * custom action per direction that is actually available, running the same
 * `HomeViewModel.moveFeed`/`reorderFolders` mutation a completed drop would.
 *
 * Both labels are shared by feed and folder rows — the direction, not the kind of row being moved,
 * is what the label has to say. A direction whose callback is `null` (the row is already first or
 * last **within its own reorder scope** — see `reorderTargetWithinScope`) exposes no action for
 * that direction at all, rather than one that would do nothing.
 *
 * @param enabled Gated by the caller on `isTouchPrimary`, exactly like [DragHandle] itself: these
 *   actions exist for the platform whose reorder gesture starts from that handle. Checked *before*
 *   resolving [moveUpLabel]/[moveDownLabel] below — desktop always passes `false` here, so without
 *   this ordering every visible feed/folder row would resolve two `stringResource` slots on every
 *   recomposition (e.g. every row, on every frame of a drag) purely to discard both immediately.
 */
@Composable
internal fun Modifier.reorderAccessibilityActions(
    enabled: Boolean,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
): Modifier {
    if (!enabled || (onMoveUp == null && onMoveDown == null)) return this
    val moveUpLabel = stringResource(Res.string.home_move_up)
    val moveDownLabel = stringResource(Res.string.home_move_down)
    val actions = buildList {
        onMoveUp?.let { move -> add(CustomAccessibilityAction(moveUpLabel) { move(); true }) }
        onMoveDown?.let { move -> add(CustomAccessibilityAction(moveDownLabel) { move(); true }) }
    }
    return this.semantics { customActions = actions }
}
