package works.merc.keryx.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** The app's flat surface pattern: `surfaceContainerLow` fill + hairline `outlineVariant` border, no tonal elevation. */
@Composable
actual fun KeryxRaisedSurface(modifier: Modifier, shape: Shape, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        content = content,
    )
}
