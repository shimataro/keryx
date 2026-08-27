package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.home_tag_color
import works.merc.keryx.app.ui.common.KeryxAnchoredPanel
import works.merc.keryx.app.ui.common.KeryxRaisedSurface

/**
 * Selectable tag colors. Chosen to stay clear of the app's Teal-based theme palette
 * (`Teal`/`TealLight` in `ui/theme/KeryxTheme.kt`) so a tag dot never reads as "the same color as
 * a selected row".
 */
private val TagColorPalette: List<String> = listOf(
    "#E53935", // red
    "#FB8C00", // orange
    "#FDD835", // amber
    "#43A047", // green
    "#1E88E5", // blue
    "#5E35B1", // indigo
    "#8E24AA", // purple
    "#D81B60", // pink
)

/**
 * Displays selectable swatches for choosing a tag color, including an option to remove the color.
 *
 * Emits into the surrounding `Column` (a label above a swatch row) and deliberately carries no
 * container of its own, so the same swatches can be hosted by the add-tag dialog's `extraContent`
 * and by [TagColorPickerPopup] — and, when a phone-width target exists, by a `ModalBottomSheet`
 * without the swatches themselves changing.
 *
 * @param selected The currently selected color value, or `null` when no color is selected.
 * @param onSelect Invoked with the selected color value, or `null` when no color is selected.
 */
@Composable
internal fun TagColorPicker(selected: String?, onSelect: (String?) -> Unit) {
    val rowDescription = stringResource(Res.string.home_tag_color)
    Spacer(Modifier.height(8.dp))
    Text(rowDescription, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = rowDescription },
    ) {
        ColorSwatch(color = Color(0xFF9E9E9E), hex = null, isSelected = selected == null, onClick = { onSelect(null) })
        TagColorPalette.forEach { hex ->
            Spacer(Modifier.width(8.dp))
            ColorSwatch(color = colorFromHex(hex), hex = hex, isSelected = selected == hex, onClick = { onSelect(hex) })
        }
    }
}

/** Test tag on one color swatch — [hex], or `null` for the "no color" swatch. */
internal fun tagColorSwatchTestTag(hex: String?): String = "tag-color-swatch-${hex ?: "none"}"

/**
 * Hosts [TagColorPicker]'s swatches in a [KeryxAnchoredPanel] opened from a tag row's color dot —
 * a non-modal anchored popover on desktop, a `ModalBottomSheet` on Android (see that composable's
 * own KDoc): picking a swatch applies immediately, so there is nothing to confirm and nothing to
 * block the rest of the window for. The `KeryxRaisedSurface` wrap is desktop-only, matching
 * `NotificationCenterSheet`'s own split — Android's `ModalBottomSheet` already supplies a container.
 *
 * @param anchorOffsetY How far below the anchor's top edge the popover is placed on desktop (i.e.
 *   the anchor's own height), so it opens just under the dot rather than over it.
 */
@Composable
internal fun TagColorPickerPopup(
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismissRequest: () -> Unit,
    anchorOffsetY: Dp,
) {
    val isTouchPrimary = works.merc.keryx.app.platform.isTouchPrimary
    KeryxAnchoredPanel(onDismissRequest = onDismissRequest, anchorOffsetY = anchorOffsetY) {
        val body: @Composable () -> Unit = {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                TagColorPicker(selected = selected, onSelect = onSelect)
            }
        }
        if (isTouchPrimary) {
            body()
        } else {
            KeryxRaisedSurface(shape = MaterialTheme.shapes.small) { body() }
        }
    }
}

/** Diameter of the drawn swatch circle — unchanged on every platform; only the click target grows. */
private val SWATCH_VISIBLE_SIZE = 24.dp

/**
 * Displays a selectable circular color swatch. On a touch-primary platform the click target grows
 * to a full 48dp Material touch target around the still-24dp drawn circle (desktop keeps the
 * click target exactly at the circle's own bounds, as before).
 *
 * @param color The swatch fill color.
 * @param hex The color value this swatch selects, or `null` for the "no color" swatch.
 * @param isSelected Whether the swatch is currently selected.
 * @param onClick The action invoked when the swatch is selected.
 */
@Composable
private fun ColorSwatch(color: Color, hex: String?, isSelected: Boolean, onClick: () -> Unit) {
    val isTouchPrimary = works.merc.keryx.app.platform.isTouchPrimary
    Box(
        Modifier
            .testTag(tagColorSwatchTestTag(hex))
            .size(if (isTouchPrimary) 48.dp else SWATCH_VISIBLE_SIZE)
            .selectable(selected = isSelected, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(SWATCH_VISIBLE_SIZE)
                .clip(CircleShape)
                .then(
                    if (isSelected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                    },
                )
                .background(color, CircleShape),
        )
    }
}

/**
 * Converts a hexadecimal color string to a color value.
 *
 * @param hex The hexadecimal color string, optionally prefixed with `#`.
 * @return The parsed color, or gray when the value is null or invalid.
 */
internal fun colorFromHex(hex: String?): Color {
    if (hex == null) return Color(0xFF9E9E9E)
    val clean = hex.removePrefix("#")
    return runCatching {
        val v = clean.toLong(16)
        if (clean.length <= 6) Color(0xFF000000 or v) else Color(v)
    }.getOrDefault(Color(0xFF9E9E9E))
}
