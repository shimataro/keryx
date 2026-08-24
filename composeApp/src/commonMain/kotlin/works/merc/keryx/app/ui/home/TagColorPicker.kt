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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.home_tag_color
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
 * Hosts [TagColorPicker]'s swatches in a lightweight anchored popover, opened from a tag row's color
 * dot. A `Popup` rather than a dialog: it is non-modal, anchored to the control that opened it, and
 * dismissed by clicking outside — and picking a swatch applies immediately, so there is nothing to
 * confirm and nothing to block the rest of the window for.
 *
 * @param anchorOffsetY How far below the anchor's top edge the popover is placed (i.e. the anchor's
 *   own height), so it opens just under the dot rather than over it.
 */
@Composable
internal fun TagColorPickerPopup(
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismissRequest: () -> Unit,
    anchorOffsetY: Dp,
) {
    val density = LocalDensity.current
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(x = 0, y = with(density) { anchorOffsetY.roundToPx() }),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
    ) {
        KeryxRaisedSurface(shape = MaterialTheme.shapes.small) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                TagColorPicker(selected = selected, onSelect = onSelect)
            }
        }
    }
}

/**
 * Displays a selectable circular color swatch.
 *
 * @param color The swatch fill color.
 * @param hex The color value this swatch selects, or `null` for the "no color" swatch.
 * @param isSelected Whether the swatch is currently selected.
 * @param onClick The action invoked when the swatch is selected.
 */
@Composable
private fun ColorSwatch(color: Color, hex: String?, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .testTag(tagColorSwatchTestTag(hex))
            .size(24.dp)
            .clip(CircleShape)
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                } else {
                    Modifier
                },
            )
            .background(color, CircleShape)
            .selectable(selected = isSelected, onClick = onClick),
    )
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
