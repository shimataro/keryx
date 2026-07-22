package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.home_tag_color

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
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = rowDescription },
    ) {
        ColorSwatch(color = Color(0xFF9E9E9E), isSelected = selected == null, onClick = { onSelect(null) })
        TagColorPalette.forEach { hex ->
            Spacer(Modifier.width(8.dp))
            ColorSwatch(color = colorFromHex(hex), isSelected = selected == hex, onClick = { onSelect(hex) })
        }
    }
}

/**
 * Displays a selectable circular color swatch.
 *
 * @param color The swatch fill color.
 * @param isSelected Whether the swatch is currently selected.
 * @param onClick The action invoked when the swatch is selected.
 */
@Composable
private fun ColorSwatch(color: Color, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
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
