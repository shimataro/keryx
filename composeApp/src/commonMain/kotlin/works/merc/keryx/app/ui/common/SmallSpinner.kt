package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A small progress spinner sized to sit in place of an icon (e.g. inside a button or toolbar).
 *
 * @param size The diameter of the indicator.
 * @param color The indicator's own color. The `primary` default is invisible inside a
 *   `primary`-filled container, so a call site sitting in one should pass
 *   `LocalContentColor.current` to follow its container's foreground instead.
 */
@Composable
fun SmallSpinner(size: Dp = 16.dp, color: Color = MaterialTheme.colorScheme.primary) {
    CircularProgressIndicator(Modifier.size(size), color = color, strokeWidth = 2.dp)
}
