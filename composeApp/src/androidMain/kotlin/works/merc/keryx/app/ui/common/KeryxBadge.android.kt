package works.merc.keryx.app.ui.common

import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.DrawableResource

/** M3's own `BadgedBox`/`Badge`, matching every other native Android surface. */
@Composable
internal actual fun KeryxBadgedIcon(icon: DrawableResource, contentDescription: String, count: Int) {
    BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) {
        KeryxIcon(icon, contentDescription = contentDescription)
    }
}
