package works.merc.keryx.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A native-looking single-select control replacing Material3's `FilterChip` row
 * (originally `ChipRow` in the settings screen) — a bordered, filled-background segmented row rather than
 * a horizontally-scrolling row of pill chips. Segments size to their label content
 * (`Modifier.weight(1f)` is deliberately not used) so labels never wrap/truncate.
 */
@Composable
fun <T> SegmentedControl(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val outerShape: Shape = MaterialTheme.shapes.small
    Row(
        Modifier
            .clip(outerShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, outerShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, outerShape)
            .selectableGroup()
            .padding(2.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Row(
                Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        MaterialTheme.shapes.extraSmall,
                    )
                    .selectable(selected = isSelected, onClick = { onSelect(value) }, role = Role.RadioButton)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A single boolean toggle rendered with the same visual language as [SegmentedControl]'s selected
 * segment (bordered, filled when checked) — replaces Material3's `FilterChip` for standalone
 * on/off controls (e.g. `ArticleListPane`'s "unread only" toggle).
 */
@Composable
fun ToggleChip(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val shape: Shape = MaterialTheme.shapes.extraSmall
    Row(
        Modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .background(
                if (checked) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape,
            )
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Checkbox)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
