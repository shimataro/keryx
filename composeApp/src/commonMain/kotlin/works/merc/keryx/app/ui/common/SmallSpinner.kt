package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A small progress spinner sized to sit in place of an icon (e.g. inside a button or toolbar). */
/**
 * Displays a compact circular progress indicator.
 *
 * @param size The diameter of the indicator.
 */
@Composable
fun SmallSpinner(size: Dp = 16.dp) {
    CircularProgressIndicator(Modifier.size(size), strokeWidth = 2.dp)
}
