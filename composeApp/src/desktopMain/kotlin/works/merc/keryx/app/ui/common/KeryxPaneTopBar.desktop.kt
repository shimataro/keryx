package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Desktop `actual`: a plain `Row`, matching each former call site's exact layout — see the
 * `expect`'s KDoc in `commonMain` for why [modifier] (not this composable) owns padding.
 */
@Composable
actual fun KeryxPaneTopBar(
    modifier: Modifier,
    title: String?,
    navigationIcon: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (navigationIcon != null) {
            navigationIcon()
            if (title != null) Spacer(Modifier.width(4.dp))
        }
        if (title != null) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
}
