package works.merc.keryx.app.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

actual object ClipboardEntries {
    @OptIn(ExperimentalComposeUiApi::class)
    actual fun ofText(text: String): ClipEntry = ClipEntry(StringSelection(text))
}
