package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.SmallSpinner

/**
 * A settings button's glyph: [icon] at [size], swapped for a [SmallSpinner] while [busy].
 *
 * @param spinnerSize The spinner's diameter; defaults to the icon's own [size].
 * @param spinnerColor The spinner's color (see [SmallSpinner]'s `color` for when to pass
 *   `LocalContentColor.current`).
 * @param contentDescription The icon's description; `null` when a label beside it already
 *   announces the action.
 */
@Composable
internal fun BusyGlyph(
    busy: Boolean,
    icon: DrawableResource,
    size: Dp,
    spinnerSize: Dp = size,
    spinnerColor: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = null,
) {
    if (busy) {
        SmallSpinner(size = spinnerSize, color = spinnerColor)
    } else {
        KeryxIcon(icon, contentDescription = contentDescription, modifier = Modifier.size(size))
    }
}

/** A labelled settings button's content: its [glyph], an 8dp gap, then its [label]. */
@Composable
internal fun GlyphLabel(label: String, glyph: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        glyph()
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
