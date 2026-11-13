package works.merc.keryx.app.ui.common

import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import org.jetbrains.compose.resources.DrawableResource

/** M3's own `BadgedBox`/`Badge`, matching every other native Android surface. */
@Composable
internal actual fun KeryxBadgedIcon(icon: DrawableResource, contentDescription: String, count: Int) {
    // The count is in the icon's contentDescription; see the expect's KDoc.
    BadgedBox(badge = { if (count > 0) Badge(Modifier.clearAndSetSemantics {}) { Text(count.toString()) } }) {
        KeryxIcon(icon, contentDescription = contentDescription)
    }
}
