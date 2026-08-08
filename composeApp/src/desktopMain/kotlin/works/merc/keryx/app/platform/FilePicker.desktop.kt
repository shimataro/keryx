package works.merc.keryx.app.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The native widgets backing one [FilePicker] call, hiding which toolkit drew them. Two exist
 * because no single one works everywhere: see [AwtFilePickerBackend] and [SwingFilePickerBackend].
 * Every method is blocking and must be called on the EDT.
 */
internal interface FilePickerBackend {
    fun pickOpen(request: OpenFileRequest, owner: Window?): String?
    fun pickSave(request: SaveFileRequest, owner: Window?): String?
}

/**
 * `java.awt.FileDialog` backend, used on macOS and Windows where AWT maps it onto the real native
 * panel (`NSSavePanel` / `GetOpenFileName`), including native overwrite prompting — so
 * [SaveFileRequest]'s `overwrite*` fields are unused here, and [OpenFileRequest.filterLabel] has
 * no UI to attach to (AWT's filter callback has no description slot).
 */
internal object AwtFilePickerBackend : FilePickerBackend {
    private fun newFileDialog(owner: Window?, title: String, mode: Int): FileDialog = when (owner) {
        is Frame -> FileDialog(owner, title, mode)
        is Dialog -> FileDialog(owner, title, mode)
        else -> FileDialog(null as Frame?, title, mode)
    }

    override fun pickOpen(request: OpenFileRequest, owner: Window?): String? {
        val dialog = newFileDialog(owner, request.title, FileDialog.LOAD)
        if (request.extensions.isNotEmpty()) {
            // Already a no-op on Windows (WFileDialogPeer ignores FilenameFilter) — pre-existing,
            // not a regression from this change.
            dialog.setFilenameFilter { _, name -> hasAnyExtension(name, request.extensions) }
        }
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        return if (dir != null && file != null) File(dir, file).absolutePath else null
    }

    override fun pickSave(request: SaveFileRequest, owner: Window?): String? {
        val dialog = newFileDialog(owner, request.title, FileDialog.SAVE)
        dialog.file = request.defaultName
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        return if (dir != null && file != null) File(dir, file).absolutePath else null
    }
}

/**
 * `javax.swing.JFileChooser` backend, used on Linux. AWT's `FileDialog` there routes through
 * `sun.awt.X11.GtkFileDialogPeer`, whose native GTK callbacks dereference a NULL `JNU_GetEnv`
 * result once WebKitGTK (the article reader's WebView) is a second GTK consumer sharing the
 * process's default `GMainContext` — a JVM-crashing SIGSEGV in `libawt_xawt.so` (see
 * `docs/known-issues.md`). `JFileChooser` is pure Swing on every Look & Feel, including the
 * FlatLaf-failed system-L&F fallback (`GTKLookAndFeel`'s `GTKFileChooserUI` is itself pure Swing),
 * so it never reaches that native code, and it picks up FlatLaf like the rest of this app's Linux UI.
 */
internal object SwingFilePickerBackend : FilePickerBackend {
    override fun pickOpen(request: OpenFileRequest, owner: Window?): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = request.title
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            if (request.extensions.isNotEmpty()) {
                // FileNameExtensionFilter (not a hand-rolled FileFilter): it also accepts
                // directories, without which the chooser could not be navigated at all.
                val filter = FileNameExtensionFilter(request.filterLabel, *request.extensions.toTypedArray())
                addChoosableFileFilter(filter)
                fileFilter = filter
                // "All files" stays available too: a hand-renamed export must still be importable.
            }
        }
        return if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else {
            null
        }
    }

    override fun pickSave(request: SaveFileRequest, owner: Window?): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = request.title
            fileSelectionMode = JFileChooser.FILES_ONLY
            selectedFile = File(currentDirectory, request.defaultName)
        }
        while (true) {
            if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return null
            val resolved = resolveSavePath(
                path = chooser.selectedFile.absolutePath,
                exists = { File(it).exists() },
                confirmOverwrite = { confirmOverwrite(owner, request) },
            )
            if (resolved != null) return resolved
            // Overwrite declined: loop back to the chooser rather than cancelling the export.
        }
    }

    private fun confirmOverwrite(owner: Window?, request: SaveFileRequest): Boolean {
        // Explicit option labels rather than YES_NO_OPTION: that constant's button text comes from
        // Swing's own locale bundles, which need jdk.localedata for ja in a jlinked runtime (see
        // the module list in build.gradle.kts) — explicit labels sidestep that entirely.
        val options = arrayOf(request.overwriteReplaceLabel, request.overwriteCancelLabel)
        val choice = JOptionPane.showOptionDialog(
            owner,
            request.overwriteMessage,
            request.overwriteTitle,
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[1],
        )
        return choice == 0
    }
}

/** Matches the AWT `FilenameFilter` predicate; shared so both backends agree. */
internal fun hasAnyExtension(name: String, extensions: List<String>): Boolean =
    extensions.any { name.endsWith(".$it", ignoreCase = true) }

/**
 * Post-processes an approved save selection: if [path] already exists, ask [confirmOverwrite]
 * before returning it. This restores the overwrite confirmation the pre-crash Linux `FileDialog`
 * already had via `gtk_file_chooser_set_do_overwrite_confirmation` (and that macOS/Windows still
 * have natively) — `JFileChooser` has no such prompt of its own.
 *
 * @return [path], or null when the user declined the overwrite.
 */
internal fun resolveSavePath(path: String, exists: (String) -> Boolean, confirmOverwrite: () -> Boolean): String? =
    if (!exists(path) || confirmOverwrite()) path else null

/** Builds the backend appropriate to the current platform. */
internal fun defaultFilePickerBackend(linux: Boolean = isLinux): FilePickerBackend =
    if (linux) SwingFilePickerBackend else AwtFilePickerBackend

/**
 * The window a native dialog should be owned by: the active (focused) top-level window, falling
 * back to any showing frame, falling back to null (AWT's shared hidden frame).
 */
internal fun <W> chooseDialogOwner(active: W?, showingFrames: List<W>): W? = active ?: showingFrames.firstOrNull()

// KeyboardFocusManager is EDT-affine (see AppMenuBarHost.kt's own use of it), so this is only ever
// called from inside the EDT hop below.
private fun resolveDialogOwner(): Window? = chooseDialogOwner(
    KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow,
    Frame.getFrames().filter { it.isShowing },
)

actual object FilePicker {
    actual suspend fun pickOpenFile(request: OpenFileRequest): String? =
        withContext(Dispatchers.Swing) { defaultFilePickerBackend().pickOpen(request, resolveDialogOwner()) }

    actual suspend fun pickSaveFile(request: SaveFileRequest): String? =
        withContext(Dispatchers.Swing) { defaultFilePickerBackend().pickSave(request, resolveDialogOwner()) }
}
