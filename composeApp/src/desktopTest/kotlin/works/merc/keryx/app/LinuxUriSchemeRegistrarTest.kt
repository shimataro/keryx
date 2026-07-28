package works.merc.keryx.app

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val LAUNCHER = "/opt/keryx/bin/Keryx"
private const val QUOTE = "\""
private const val BACKSLASH = "\\"

class LinuxUriSchemeRegistrarTest {
    private lateinit var root: File
    private lateinit var applicationsDir: File
    private lateinit var mimeAppsList: File
    private val refreshes = mutableListOf<File>()

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("uri-scheme-registrar-test").toFile()
        // Deliberately nested under directories that do not exist yet — a minimal Linux install
        // may have neither ~/.local/share/applications nor ~/.config.
        applicationsDir = File(root, "share/applications")
        mimeAppsList = File(root, "config/mimeapps.list")
        refreshes.clear()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun registrar(
        launcherPath: String = LAUNCHER,
        refresh: (File) -> Unit = { refreshes += it },
    ) = LinuxUriSchemeRegistrar(launcherPath, applicationsDir, mimeAppsList, refresh)

    private fun desktopFile() = File(applicationsDir, URI_HANDLER_DESKTOP_FILE)

    // --- .desktop entry -----------------------------------------------------------------

    @Test
    fun desktopEntryDeclaresTheSchemeHandler() {
        val entry = desktopEntryContent(LAUNCHER, CUSTOM_URI_MIME_TYPE, "%u")
        assertTrue(entry.startsWith("[Desktop Entry]\n"), entry)
        assertTrue(entry.contains("\nType=Application\n"), entry)
        assertTrue(entry.contains("\nName=Keryx\n"), entry)
        assertTrue(entry.contains("\nTerminal=false\n"), entry)
        assertTrue(entry.contains("\nNoDisplay=true\n"), entry)
        assertTrue(entry.contains("\nStartupNotify=false\n"), entry)
        assertTrue(entry.contains("\nMimeType=x-scheme-handler/keryx;\n"), entry)
    }

    @Test
    fun execPassesTheUriAsAnArgument() {
        // Without the %u field code the desktop entry spec does not hand the URI to the process,
        // so main()'s argv scan never sees the OAuth callback. This is the whole point of the file.
        assertTrue(desktopEntryContent(LAUNCHER, CUSTOM_URI_MIME_TYPE, "%u").contains("\nExec=\"$LAUNCHER\" %u\n"))
    }

    @Test
    fun execQuotesPathsContainingSpaces() {
        assertEquals(
            "$QUOTE/home/u/My Apps/Keryx/bin/Keryx$QUOTE",
            escapeDesktopExecPath("/home/u/My Apps/Keryx/bin/Keryx"),
        )
    }

    @Test
    fun execEscapesReservedCharacters() {
        // Each reserved character is backslash-escaped for the Exec argument, then that backslash
        // is doubled again by the desktop-entry value escaping.
        assertEquals(
            QUOTE + "/opt/a" + BACKSLASH.repeat(2) + QUOTE + "b" + QUOTE,
            escapeDesktopExecPath("""/opt/a"b"""),
        )
        assertEquals(
            QUOTE + "/opt/a" + BACKSLASH.repeat(2) + "\$b" + QUOTE,
            escapeDesktopExecPath("/opt/a\$b"),
        )
        assertEquals(
            QUOTE + "/opt/a" + BACKSLASH.repeat(2) + "`b" + QUOTE,
            escapeDesktopExecPath("/opt/a`b"),
        )
        assertEquals(
            QUOTE + "/opt/a" + BACKSLASH.repeat(4) + "b" + QUOTE,
            escapeDesktopExecPath("""/opt/a\b"""),
        )
    }

    @Test
    fun execDoublesLiteralPercentSigns() {
        // A bare % would be read as a field code rather than part of the path.
        assertEquals("$QUOTE/opt/a%%b$QUOTE", escapeDesktopExecPath("/opt/a%b"))
    }

    // --- mimeapps.list merge ------------------------------------------------------------

    @Test
    fun createsSectionsWhenTheFileDoesNotExist() {
        assertEquals(
            """
            [Default Applications]
            x-scheme-handler/keryx=keryx-url-handler.desktop

            [Added Associations]
            x-scheme-handler/keryx=keryx-url-handler.desktop;
            """.trimIndent() + "\n",
            mergeMimeAppsList(null, URI_HANDLER_DESKTOP_FILE, CUSTOM_URI_MIME_TYPE),
        )
    }

    @Test
    fun preservesUnrelatedEntries() {
        val existing = """
            # user overrides
            [Added Associations]
            text/html=firefox.desktop;

            [Default Applications]
            text/html=firefox.desktop
            x-scheme-handler/http=firefox.desktop
        """.trimIndent() + "\n"

        assertEquals(
            """
            # user overrides
            [Added Associations]
            text/html=firefox.desktop;
            x-scheme-handler/keryx=keryx-url-handler.desktop;

            [Default Applications]
            text/html=firefox.desktop
            x-scheme-handler/http=firefox.desktop
            x-scheme-handler/keryx=keryx-url-handler.desktop
            """.trimIndent() + "\n",
            mergeMimeAppsList(existing, URI_HANDLER_DESKTOP_FILE, CUSTOM_URI_MIME_TYPE),
        )
    }

    @Test
    fun replacesAStaleHandler() {
        val existing = """
            [Default Applications]
            x-scheme-handler/keryx=old-keryx.desktop
        """.trimIndent() + "\n"

        val merged = mergeMimeAppsList(existing, URI_HANDLER_DESKTOP_FILE, CUSTOM_URI_MIME_TYPE)
        assertFalse(merged.contains("old-keryx.desktop"), merged)
        assertTrue(merged.contains("x-scheme-handler/keryx=keryx-url-handler.desktop\n"), merged)
    }

    @Test
    fun appendsToExistingAddedAssociationsWithoutDroppingThem() {
        val existing = """
            [Added Associations]
            x-scheme-handler/keryx=other.desktop;
        """.trimIndent() + "\n"

        val merged = mergeMimeAppsList(existing, URI_HANDLER_DESKTOP_FILE, CUSTOM_URI_MIME_TYPE)
        assertTrue(
            merged.contains("x-scheme-handler/keryx=other.desktop;keryx-url-handler.desktop;\n"),
            merged,
        )
    }

    @Test
    fun returnsTheInputUnchangedWhenAlreadyRegistered() {
        val once = mergeMimeAppsList(null, URI_HANDLER_DESKTOP_FILE, CUSTOM_URI_MIME_TYPE)
        assertEquals(once, mergeMimeAppsList(once, URI_HANDLER_DESKTOP_FILE, CUSTOM_URI_MIME_TYPE))
    }

    @Test
    fun handlesAFileWithoutATrailingNewline() {
        val existing = "[Default Applications]\ntext/html=firefox.desktop"
        val merged = mergeMimeAppsList(existing, URI_HANDLER_DESKTOP_FILE, CUSTOM_URI_MIME_TYPE)
        assertFalse(merged.endsWith("\n"), merged)
        assertTrue(merged.contains("text/html=firefox.desktop\n"), merged)
        assertTrue(merged.contains("x-scheme-handler/keryx=keryx-url-handler.desktop"), merged)
    }

    // --- register() ---------------------------------------------------------------------

    @Test
    fun registerWritesBothFilesAndRefreshesTheDesktopDatabase() {
        assertTrue(registrar().register())

        assertEquals(desktopEntryContent(LAUNCHER, CUSTOM_URI_MIME_TYPE, "%u"), desktopFile().readText())
        assertTrue(
            mimeAppsList.readText().contains("x-scheme-handler/keryx=keryx-url-handler.desktop\n"),
            mimeAppsList.readText(),
        )
        assertEquals(listOf(applicationsDir), refreshes)
    }

    @Test
    fun registerIsIdempotent() {
        assertTrue(registrar().register())
        val writtenAt = desktopFile().lastModified()

        assertTrue(registrar().register())

        assertEquals(writtenAt, desktopFile().lastModified())
        assertEquals(1, refreshes.size, "an unchanged registration must not spawn a subprocess")
    }

    @Test
    fun registerSucceedsWhenUpdateDesktopDatabaseIsMissing() {
        val registrar = registrar(refresh = { throw IOException("update-desktop-database not found") })

        assertTrue(registrar.register())

        assertEquals(desktopEntryContent(LAUNCHER, CUSTOM_URI_MIME_TYPE, "%u"), desktopFile().readText())
        assertTrue(mimeAppsList.readText().contains("keryx-url-handler.desktop"))
    }

    @Test
    fun registerRepairsAStaleLauncherPath() {
        assertTrue(registrar(launcherPath = "/old/path/Keryx").register())

        assertTrue(registrar(launcherPath = LAUNCHER).register())

        assertEquals(desktopEntryContent(LAUNCHER, CUSTOM_URI_MIME_TYPE, "%u"), desktopFile().readText())
        assertEquals(2, refreshes.size)
    }

    @Test
    fun registerLeavesUnrelatedAssociationsInPlace() {
        mimeAppsList.parentFile.mkdirs()
        mimeAppsList.writeText("[Default Applications]\ntext/html=firefox.desktop\n")

        assertTrue(registrar().register())

        val merged = mimeAppsList.readText()
        assertTrue(merged.contains("text/html=firefox.desktop\n"), merged)
        assertTrue(merged.contains("x-scheme-handler/keryx=keryx-url-handler.desktop\n"), merged)
    }

    // --- runProcessWithTimeout ------------------------------------------------------------

    @Test
    fun runProcessWithTimeoutAbortsAHungProcess() {
        val elapsed = measureTimeMillis {
            assertFailsWith<IOException> { runProcessWithTimeout(listOf("sleep", "5"), timeoutMillis = 200) }
        }
        assertTrue(elapsed < 4_000, "expected the timeout to abort quickly, took ${elapsed}ms")
    }

    @Test
    fun runProcessWithTimeoutReturnsNormallyForAFastProcess() {
        assertEquals(0, runProcessWithTimeout(listOf("echo", "hi"), timeoutMillis = 5_000))
    }
}
