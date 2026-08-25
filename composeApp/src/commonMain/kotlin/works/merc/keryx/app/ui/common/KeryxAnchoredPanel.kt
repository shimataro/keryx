package works.merc.keryx.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A lightweight, dismissable panel opened from a control (the notification bell, a tag's color
 * dot) — the replacement for a raw `androidx.compose.ui.window.Popup` at a `commonMain` call site
 * (see `ui/home/ArticleRowComponents.kt`'s `NotificationsBell` and
 * `ui/home/TagColorPicker.kt`'s `TagColorPickerPopup`, both now thin wrappers around this).
 *
 * Desktop's `actual` is exactly the former raw `Popup` (anchored, focusable,
 * dismiss-on-click-outside) — see the `ui-guidelines` skill's "Popup vs. Dialog" section for why
 * this stays a `Popup` rather than a `Dialog` there: non-modal, no scrim, dismissed by clicking
 * outside, and picking something inside applies immediately with nothing to confirm. Android's
 * `actual` is a real M3 `ModalBottomSheet`, matching that platform's own convention for this kind
 * of lightweight overlay — [content] should render its own text/controls only, with **no**
 * `KeryxRaisedSurface`/shadow/width wrapping of its own, since both `actual`s already provide a
 * container (a bare `Popup` has none on desktop, so the caller's content there still needs to
 * supply the desktop-flat surface itself — only Android's `ModalBottomSheet` container is
 * automatic).
 *
 * @param alignment Anchor alignment on desktop (ignored on Android, where a bottom sheet has no
 *   anchor of its own).
 * @param anchorOffsetY How far below the anchor's top edge to open, on desktop only.
 */
@Composable
expect fun KeryxAnchoredPanel(
    onDismissRequest: () -> Unit,
    alignment: Alignment = Alignment.TopStart,
    anchorOffsetY: Dp = 0.dp,
    content: @Composable () -> Unit,
)
