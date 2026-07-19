package works.merc.keryx.app.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual object FilePicker {
    actual suspend fun pickOpenFile(title: String, extensions: List<String>): String? =
        withContext(Dispatchers.Default) {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            if (extensions.isNotEmpty()) {
                dialog.setFilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
            }
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            if (dir != null && file != null) File(dir, file).absolutePath else null
        }

    actual suspend fun pickSaveFile(title: String, defaultName: String): String? =
        withContext(Dispatchers.Default) {
            val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
            dialog.file = defaultName
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            if (dir != null && file != null) File(dir, file).absolutePath else null
        }
}
