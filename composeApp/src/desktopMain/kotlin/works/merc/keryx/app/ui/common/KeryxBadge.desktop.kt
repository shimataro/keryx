package works.merc.keryx.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource

/** A hand-rolled pill badge, matching the app's own flat, non-Material chrome on desktop. */
@Composable
internal actual fun KeryxBadgedIcon(icon: DrawableResource, contentDescription: String, count: Int) {
    Box(contentAlignment = Alignment.TopEnd) {
        KeryxIcon(icon, contentDescription = contentDescription)
        // The count is in the icon's contentDescription; see the expect's KDoc.
        Box(Modifier.clearAndSetSemantics {}.offset(x = 6.dp, y = (-4).dp)) {
            KeryxCountBadge(count)
        }
    }
}

@Composable
internal actual fun KeryxCountBadge(count: Int) {
    if (count <= 0) return
    Box(
        Modifier
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
