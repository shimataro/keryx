package works.merc.keryx.app.platform

import java.io.File
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the pure decision logic behind the Linux/macOS/Windows file-dialog split — backend
 * selection, the extension predicate shared with `FileNameExtensionFilter`, the save-path overwrite
 * resolution, and dialog-owner selection. None of this needs a display; actually showing either
 * dialog is a manual check (see `docs/testing.md`).
 */
class FilePickerTest {

    @Test
    fun defaultFilePickerBackendUsesSwingOnLinuxAndAwtElsewhere() {
        assertIs<SwingFilePickerBackend>(defaultFilePickerBackend(linux = true))
        assertIs<AwtFilePickerBackend>(defaultFilePickerBackend(linux = false))
    }

    @Test
    fun hasAnyExtensionMatchesCaseInsensitivelyOnTheSuffixOnly() {
        val extensions = listOf("opml", "xml")

        assertTrue(hasAnyExtension("feeds.OPML", extensions))
        assertTrue(hasAnyExtension("feeds.xml", extensions))
        assertFalse(hasAnyExtension("notopml", extensions))
        assertFalse(hasAnyExtension("opml", extensions))
        assertFalse(hasAnyExtension("feeds.opml.txt", extensions))
    }

    @Test
    fun fileNameExtensionFilterAgreesWithTheSharedPredicateAndAcceptsDirectories() {
        val extensions = listOf("opml", "xml")
        val filter = FileNameExtensionFilter("OPML files", *extensions.toTypedArray())
        val names = listOf("feeds.opml", "feeds.OPML", "feeds.xml", "notopml", "opml", "feeds.opml.txt")

        for (name in names) {
            assertEquals(hasAnyExtension(name, extensions), filter.accept(File(name)), "mismatch for $name")
        }
        // A filter that rejected directories would make the chooser un-navigable.
        assertTrue(filter.accept(File(".")))
    }

    @Test
    fun resolveSavePathSkipsConfirmationWhenNothingExists() {
        var confirmCalled = false

        val result = resolveSavePath("/tmp/new.opml", exists = { false }, confirmOverwrite = { confirmCalled = true; true })

        assertEquals("/tmp/new.opml", result)
        assertFalse(confirmCalled)
    }

    @Test
    fun resolveSavePathReturnsNullWhenTheOverwriteIsDeclined() {
        val result = resolveSavePath("/tmp/existing.opml", exists = { true }, confirmOverwrite = { false })

        assertNull(result)
    }

    @Test
    fun resolveSavePathReturnsThePathWhenTheOverwriteIsConfirmed() {
        val result = resolveSavePath("/tmp/existing.opml", exists = { true }, confirmOverwrite = { true })

        assertEquals("/tmp/existing.opml", result)
    }

    @Test
    fun chooseDialogOwnerPrefersTheActiveWindow() {
        assertEquals("active", chooseDialogOwner("active", listOf("frame1", "frame2")))
    }

    @Test
    fun chooseDialogOwnerFallsBackToTheFirstShowingFrame() {
        assertEquals("frame1", chooseDialogOwner(null, listOf("frame1", "frame2")))
    }

    @Test
    fun chooseDialogOwnerYieldsNullWhenNothingIsShowing() {
        assertNull(chooseDialogOwner<String>(null, emptyList()))
    }
}
