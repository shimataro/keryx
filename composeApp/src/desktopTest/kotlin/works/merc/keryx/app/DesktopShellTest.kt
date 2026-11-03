package works.merc.keryx.app

import works.merc.keryx.app.ui.menu.MenuController
import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [installAppEventHandlers] against a recording [AppEventRegistry], since AWT's own event types
 * cannot be built headless: a test drives the handler the registration handed over.
 */
class DesktopShellTest {

    private class FakeRegistry(private val supported: Set<Desktop.Action>) : AppEventRegistry {
        var openFiles: ((List<File>) -> Unit)? = null

        override fun isSupported(action: Desktop.Action): Boolean = action in supported

        override fun onAbout(handler: () -> Unit) = Unit

        override fun onPreferences(handler: () -> Unit) = Unit

        override fun onOpenUri(handler: (URI) -> Unit) = Unit

        override fun onOpenFiles(handler: (List<File>) -> Unit) {
            openFiles = handler
        }

        override fun onReopened(handler: () -> Unit) = Unit
    }

    private fun install(registry: AppEventRegistry?, opened: MutableList<String>) {
        installAppEventHandlers(
            menuController = MenuController(),
            onOAuthCallback = {},
            onOpmlFile = { opened += it },
            onReopen = {},
            registry = registry,
        )
    }

    @Test
    fun `an open-file event passes only the opml files on, whatever the case`() {
        val registry = FakeRegistry(setOf(Desktop.Action.APP_OPEN_FILE))
        val opened = mutableListOf<String>()
        install(registry, opened)

        val handler = assertNotNull(registry.openFiles, "the open-file handler was registered")
        handler(listOf(File("a.opml"), File("b.txt"), File("C.OPML")))

        assertEquals(listOf(File("a.opml").absolutePath, File("C.OPML").absolutePath), opened)
    }

    @Test
    fun `no open-file handler is registered where the action is unsupported`() {
        val registry = FakeRegistry(supported = emptySet())

        install(registry, mutableListOf())

        assertNull(registry.openFiles)
    }

    @Test
    fun `no registry registers nothing and does not fail`() {
        install(registry = null, opened = mutableListOf())
    }
}
