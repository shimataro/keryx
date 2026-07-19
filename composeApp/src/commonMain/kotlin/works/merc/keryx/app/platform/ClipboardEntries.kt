package works.merc.keryx.app.platform

import androidx.compose.ui.platform.ClipEntry

/** Builds native [ClipEntry] values for clipboard-copy actions (plain text only, for now). */
expect object ClipboardEntries {
    fun ofText(text: String): ClipEntry
}
