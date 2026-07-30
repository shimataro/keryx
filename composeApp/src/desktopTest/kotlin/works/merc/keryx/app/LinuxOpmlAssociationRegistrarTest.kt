package works.merc.keryx.app

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val LAUNCHER = "/opt/keryx/bin/Keryx"

class LinuxOpmlAssociationRegistrarTest {
    private lateinit var root: File
    private lateinit var applicationsDir: File
    private lateinit var mimeAppsList: File
    private lateinit var mimePackagesDir: File
    private val desktopRefreshes = mutableListOf<File>()
    private val mimeRefreshes = mutableListOf<File>()

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("opml-association-registrar-test").toFile()
        // Deliberately nested under directories that do not exist yet — a minimal Linux install
        // may have neither ~/.local/share/applications, ~/.config, nor ~/.local/share/mime/packages.
        applicationsDir = File(root, "share/applications")
        mimeAppsList = File(root, "config/mimeapps.list")
        mimePackagesDir = File(root, "share/mime/packages")
        desktopRefreshes.clear()
        mimeRefreshes.clear()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun registrar(
        launcherPath: String = LAUNCHER,
        refreshDesktop: (File) -> Unit = { desktopRefreshes += it },
        refreshMime: (File) -> Unit = { mimeRefreshes += it },
    ) = LinuxOpmlAssociationRegistrar(launcherPath, applicationsDir, mimeAppsList, mimePackagesDir, refreshDesktop, refreshMime)

    private fun desktopFile() = File(applicationsDir, OPML_HANDLER_DESKTOP_FILE)
    private fun mimePackageFile() = File(mimePackagesDir, OPML_MIME_PACKAGE_FILE)

    // --- .desktop entry -----------------------------------------------------------------

    @Test
    fun desktopEntryDeclaresBothOpmlMimeTypesAndHandsOverABarePath() {
        val entry = desktopEntryContent(LAUNCHER, OPML_DESKTOP_MIME_TYPES.joinToString(";"), "%f")
        assertTrue(entry.contains("\nMimeType=application/x-opml+xml;text/x-opml;\n"), entry)
        // %f (not %u) since the file manager invokes this with a plain filesystem path, matching
        // what classifyLaunchArg expects — no file:// URI form to strip.
        assertTrue(entry.contains("\nExec=\"$LAUNCHER\" %f\n"), entry)
    }

    // --- shared-mime-info package ---------------------------------------------------------

    @Test
    fun mimePackageMapsTheOpmlGlobToOnlyThePrimaryMimeType() {
        val content = opmlMimePackageContent()
        assertTrue(content.contains("type=\"application/x-opml+xml\""), content)
        assertTrue(content.contains("<glob pattern=\"*.opml\"/>"), content)
        // The alternate MIME type is only ever declared as an additional openable type (see
        // desktopEntryDeclaresBothOpmlMimeTypesAndHandsOverABarePath) — it must never also claim
        // the *.opml glob here, or Keryx's own package would ambiguously bind the same glob to two
        // different types.
        assertFalse(content.contains(OPML_MIME_TYPE_ALT), content)
    }

    // --- register() ---------------------------------------------------------------------

    @Test
    fun registerWritesAllThreeFilesAndRefreshesBothDatabases() {
        assertTrue(registrar().register())

        assertEquals(
            desktopEntryContent(LAUNCHER, OPML_DESKTOP_MIME_TYPES.joinToString(";"), "%f"),
            desktopFile().readText(),
        )
        assertEquals(opmlMimePackageContent(), mimePackageFile().readText())
        val associations = mimeAppsList.readText()
        assertTrue(associations.contains("application/x-opml+xml=keryx-opml-handler.desktop\n"), associations)
        assertTrue(associations.contains("text/x-opml=keryx-opml-handler.desktop\n"), associations)
        assertEquals(listOf(applicationsDir), desktopRefreshes)
        assertEquals(listOf(mimePackagesDir.parentFile), mimeRefreshes)
    }

    @Test
    fun registerIsIdempotent() {
        assertTrue(registrar().register())
        val writtenAt = desktopFile().lastModified()

        assertTrue(registrar().register())

        assertEquals(writtenAt, desktopFile().lastModified())
        assertEquals(1, desktopRefreshes.size, "an unchanged registration must not spawn a subprocess")
        assertEquals(1, mimeRefreshes.size, "an unchanged registration must not spawn a subprocess")
    }

    @Test
    fun registerFailsWhenUpdateMimeDatabaseIsMissing() {
        val registrar = registrar(refreshMime = { throw IOException("update-mime-database not found") })

        assertFalse(registrar.register())

        // The package file must be rolled back, not left believing the mapping is installed while
        // the compiled cache never actually got rebuilt.
        assertFalse(mimePackageFile().exists())
    }

    @Test
    fun registerRetriesMimeDatabaseRefreshAfterAPriorFailure() {
        val failing = registrar(refreshMime = { throw IOException("update-mime-database not found") })
        assertFalse(failing.register())
        assertFalse(mimePackageFile().exists())

        assertTrue(registrar().register())

        assertEquals(opmlMimePackageContent(), mimePackageFile().readText())
        assertEquals(1, mimeRefreshes.size, "the retry must actually invoke the refresh, not skip it as unchanged")
    }

    @Test
    fun registerLeavesUnrelatedAssociationsInPlace() {
        mimeAppsList.parentFile.mkdirs()
        mimeAppsList.writeText("[Default Applications]\ntext/html=firefox.desktop\n")

        assertTrue(registrar().register())

        val merged = mimeAppsList.readText()
        assertTrue(merged.contains("text/html=firefox.desktop\n"), merged)
        assertTrue(merged.contains("application/x-opml+xml=keryx-opml-handler.desktop\n"), merged)
        assertTrue(merged.contains("text/x-opml=keryx-opml-handler.desktop\n"), merged)
    }

    @Test
    fun registerDoesNotTouchTheUriSchemeDesktopFile() {
        // The two registrars write separate files (not one shared entry), so registering one
        // doesn't clobber or duplicate the other's association.
        assertTrue(registrar().register())

        assertTrue(File(applicationsDir, URI_HANDLER_DESKTOP_FILE).exists().not())
    }
}
