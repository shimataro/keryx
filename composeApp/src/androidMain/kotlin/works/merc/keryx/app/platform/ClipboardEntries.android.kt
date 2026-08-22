package works.merc.keryx.app.platform

import android.content.ClipData
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

actual object ClipboardEntries {
    @OptIn(ExperimentalComposeUiApi::class)
    actual fun ofText(text: String): ClipEntry =
        ClipEntry(ClipData.newPlainText(text, text))
}
