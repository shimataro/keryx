package works.merc.keryx.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/**
 * M3's own elevation idiom: a distinctly-tinted tonal container tier
 * ([androidx.compose.material3.ColorScheme.surfaceContainerHigh]) rather than desktop's
 * hairline-border flat card, and no border at all — a bordered card reads as desktop chrome on
 * Android, where a tonal surface already reads as "raised" on its own.
 */
@Composable
actual fun KeryxRaisedSurface(modifier: Modifier, shape: Shape, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = content,
    )
}
