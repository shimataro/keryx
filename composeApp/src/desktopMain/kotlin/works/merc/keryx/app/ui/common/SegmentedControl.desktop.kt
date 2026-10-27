package works.merc.keryx.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
actual fun <T> SegmentedControl(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
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

@Composable
actual fun MenuChip(label: String, accessibilityLabel: String, highlighted: Boolean, onClick: () -> Unit) {
    val shape: Shape = MaterialTheme.shapes.extraSmall
    val contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .background(if (highlighted) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .clickable(role = Role.DropdownList, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
                stateDescription = label
            }
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
        KeryxIcon(
            KeryxIcons.ExpandMore,
            contentDescription = null,
            modifier = Modifier.padding(start = 2.dp).size(16.dp),
            tint = contentColor,
        )
    }
}
