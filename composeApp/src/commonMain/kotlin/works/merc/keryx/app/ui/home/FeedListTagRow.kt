package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.platform.NativeMenuItem
import works.merc.keryx.app.platform.nativeContextMenu
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.home_change_tag_color_menu
import works.merc.keryx.app.resources.home_delete_tag_menu
import works.merc.keryx.app.resources.home_edit_tag_menu
import works.merc.keryx.app.resources.home_tag_color
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons

/**
 * Renders a tag row: selection/filter target, expand toggle for its attached-feed list, and the
 * highlight for a feed drag currently hovering it (attach, handled by the pane's single centralized
 * drag host — see `FeedListPane`'s outer `Box`).
 *
 * The drop affordance is deliberately different from a folder's: attaching a tag is additive (a feed
 * may carry many tags) whereas dropping on a folder *moves* the feed, so this row tints itself and
 * its border `tertiary`/`tertiaryContainer` — not the `secondary`/`secondaryContainer` of
 * [FolderGroupHeader] — and additionally swaps its color dot for a filled "+" badge while hovered
 * (a folder gets no such badge, since a move has no equivalent "adding" semantics). This "attach,
 * not move" cue is deliberately drawn on the target row rather than on the drag ghost itself: an
 * affordance drawn on the target it applies to reads more clearly than one riding along with the
 * pointer, and it keeps the ghost identical no matter what is underneath it.
 *
 * @param tag The tag represented by the row.
 * @param count The number of unread articles associated with the tag.
 * @param expanded Whether attached feeds are displayed.
 * @param selected Whether the tag is the active filter.
 * @param focused Whether the sidebar currently has focus.
 * @param isDropTarget Whether a dragged feed is currently hovering this tag.
 * @param onToggleExpanded Toggles the attached-feed list.
 * @param onClick Selects the tag.
 * @param onEdit Starts inline editing of the tag's name.
 * @param onDelete Deletes the tag.
 * @param editingName Whether the name is currently open for inline editing (see [InlineRenameField]).
 * @param onRenameCommit Applies an edited tag name.
 * @param onRenameCancel Abandons an in-progress name edit.
 * @param nameError Produces a validation message for an edited name, or `null` when valid.
 * @param onSelectColor Applies a color picked from the color dot's popover, reachable either by
 *   tapping the dot directly or via the row's own context menu's "change color" entry (both open
 *   the same popover, anchored to the dot). Independent of name editing: the dot is clickable
 *   whether or not the row is currently being renamed.
 * @param isTouchPrimary Overridable for tests only — see `feedListReorderDrag`'s own KDoc.
 */
@Composable
internal fun TagRow(
    tag: Tags,
    count: Long,
    expanded: Boolean,
    selected: Boolean,
    focused: Boolean,
    isDropTarget: Boolean,
    onToggleExpanded: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    editingName: Boolean = false,
    onRenameCommit: (String) -> Unit = {},
    onRenameCancel: () -> Unit = {},
    nameError: (String) -> String? = { null },
    onSelectColor: (String?) -> Unit = {},
    isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary,
) {
    val editLabel = stringResource(Res.string.home_edit_tag_menu)
    val changeColorLabel = stringResource(Res.string.home_change_tag_color_menu)
    val deleteLabel = stringResource(Res.string.home_delete_tag_menu)
    val colorLabel = stringResource(Res.string.home_tag_color)
    var showColorPicker by remember { mutableStateOf(false) }
    val contentColor = dropTargetContentColorOrNull(isDropTarget, selected, focused, MaterialTheme.colorScheme.onTertiaryContainer)
    val rowInteraction = remember { MutableInteractionSource() }
    Row(
        Modifier.testTag(tagRowTestTag(tag.id))
            .fillMaxWidth()
            .listRowClickable(rowInteraction, selected, onClick)
            .nativeContextMenu(
                items = {
                    listOf(
                        NativeMenuItem(editLabel, renameNativeShortcut) { onEdit() },
                        NativeMenuItem(changeColorLabel) { showColorPicker = true },
                        NativeMenuItem(deleteLabel, deleteNativeShortcut) { onDelete() },
                    )
                },
                onOpen = { if (!selected) onClick() },
            )
            .listRowSurface(
                dropTargetBackground(isDropTarget, selected, focused, MaterialTheme.colorScheme.tertiaryContainer),
                ListRowKind.NavItem,
                rowInteraction,
                decoration = listRowOutline(
                    ListRowKind.NavItem,
                    selected,
                    focused,
                    dropTargetColor = MaterialTheme.colorScheme.tertiary.takeIf { isDropTarget },
                ),
            )
            .heightIn(min = listRowMinHeight(isTouchPrimary))
            .padding(start = FEED_LIST_ROW_START_PADDING, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides (contentColor ?: LocalContentColor.current)) {
            ExpandCollapseChevron(expanded = expanded, onToggle = onToggleExpanded, isTouchPrimary = isTouchPrimary)
            Spacer(Modifier.width(CHEVRON_MARKER_GAP))
            Row(
                Modifier.weight(1f).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Anchors the color popover; sized by the click target inside it.
                Box {
                    Box(
                        // The click target's *layout* footprint (TAG_COLOR_DOT_FOOTPRINT, 26x26 —
                        // the FEED_LIST_MARKER_SLOT marker plus the padding below) is the same on
                        // both platforms, so the tag row's own hierarchy geometry never depends on
                        // touch density (see `feedRowIndent` in `FeedListDragAndDrop.kt`). A
                        // touch-primary platform additionally grows the *hit target* past that
                        // footprint to a full Material 48dp square via `layoutAs`, safe now that
                        // the row's own listRowMinHeight() floor keeps this from stretching the row
                        // taller than its neighbors. `clickable` must come after `layoutAs`/
                        // `requiredSize` (like `ExpandCollapseChevron`'s own touch-primary branch)
                        // — `layoutAs` always reports its own fixed size regardless of what a child
                        // measures, so a `clickable` placed *before* it would only ever see the
                        // 26dp footprint, not the enlarged 48dp target.
                        Modifier
                            .testTag(tagColorDotTestTag(tag.id))
                            .then(
                                if (isTouchPrimary) {
                                    Modifier.layoutAs(TAG_COLOR_DOT_FOOTPRINT, TAG_COLOR_DOT_FOOTPRINT).requiredSize(TOUCH_TARGET_MIN_SIZE)
                                } else {
                                    Modifier
                                },
                            )
                            .clickable(onClickLabel = colorLabel) { showColorPicker = true }
                            .padding(top = 4.dp, bottom = 4.dp, end = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Fixed-size slot so swapping the dot for the "+" badge never shifts the tag name.
                        Box(Modifier.size(FEED_LIST_MARKER_SLOT), contentAlignment = Alignment.Center) {
                            if (isDropTarget) {
                                Box(
                                    Modifier.size(FEED_LIST_MARKER_SLOT).background(MaterialTheme.colorScheme.tertiary, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    KeryxIcon(
                                        KeryxIcons.Add,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onTertiary,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            } else {
                                Box(Modifier.size(TAG_COLOR_DOT_SIZE_DP.dp).background(colorFromHex(tag.color), CircleShape))
                            }
                        }
                    }
                    if (showColorPicker) {
                        TagColorPickerPopup(
                            selected = tag.color,
                            onSelect = { showColorPicker = false; onSelectColor(it) },
                            onDismissRequest = { showColorPicker = false },
                            anchorOffsetY = TAG_COLOR_DOT_FOOTPRINT,
                        )
                    }
                }
                // Same weighted slot either way, so the color dot on the left and the count badge on
                // the right never move when editing starts or ends.
                if (editingName) {
                    Box(Modifier.weight(1f)) {
                        InlineRenameField(
                            value = tag.name,
                            onCommit = onRenameCommit,
                            onCancel = onRenameCancel,
                            blockingError = nameError,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Text(tag.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (count > 0) CountBadge(count, selected, focused, isDropTarget, onContainerColor = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

/** Diameter of the visible tag color dot inside the [FEED_LIST_MARKER_SLOT] marker slot. */
private const val TAG_COLOR_DOT_SIZE_DP = 10

/**
 * The color dot's click target *layout* footprint — [FEED_LIST_MARKER_SLOT] plus the same
 * `padding(top = 4.dp, bottom = 4.dp, end = 8.dp)` every [TagRow] applies around it, absorbing
 * what used to be a plain `Spacer` there so the dot's own hit area is not just a bare 10dp circle.
 * The same on every platform — a touch-primary platform's larger Material hit target is layered on
 * top of this footprint via `Modifier.layoutAs` rather than replacing it, so [TagRow]'s own
 * hierarchy geometry never depends on touch density (see `feedRowIndent` in
 * `FeedListDragAndDrop.kt`).
 */
private val TAG_COLOR_DOT_FOOTPRINT = FEED_LIST_MARKER_SLOT + 8.dp

/** Test tag on a tag row's color dot, which opens its color popover. */
internal fun tagColorDotTestTag(tagId: String): String = "tag-color-dot-$tagId"

/** Test tag on a [TagRow] itself, distinguishing its clickable band from its color dot. */
internal fun tagRowTestTag(tagId: String): String = "tag-row-$tagId"
