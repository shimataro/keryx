package works.merc.keryx.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Flat, native-feel replacements for M3's `Switch`/`Checkbox`, built on plain `Modifier.toggleable`
 * (no `indication` override) so presses use the app-wide flat
 * [androidx.compose.foundation.LocalIndication] instead of M3's hardcoded ripple. Same visual
 * language as [SegmentedControl]/[ToggleChip] (hairline `outlineVariant` border, `primary` fill when
 * on, `onPrimary` content). See `.claude/ui-guidelines.md`. Don't use M3's `Switch`/`Checkbox`
 * directly at a call site.
 */
@Composable
fun FlatSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val trackShape = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.38f)
            .size(width = 40.dp, height = 24.dp)
            .clip(trackShape)
            .then(
                if (checked) {
                    Modifier.background(MaterialTheme.colorScheme.primary, trackShape)
                } else {
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, trackShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, trackShape)
                },
            )
            .toggleable(value = checked, enabled = enabled, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(horizontal = 3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(
                    if (checked) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    CircleShape,
                ),
        )
    }
}

@Composable
fun FlatCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.38f)
            .size(20.dp)
            .clip(shape)
            .then(
                if (checked) {
                    Modifier.background(MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                },
            )
            .toggleable(value = checked, enabled = enabled, onValueChange = onCheckedChange, role = Role.Checkbox),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
