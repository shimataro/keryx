package works.merc.keryx.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.ui.menu.MenuCommand
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
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopShellTest {

    private class FakeRegistry(
        private val supported: Set<Desktop.Action>,
        private val failSupportCheck: Desktop.Action? = null,
        private val failAboutRegistration: Boolean = false,
    ) : AppEventRegistry {
        var about: (() -> Unit)? = null
        var preferences: (() -> Unit)? = null
        var openFiles: ((List<File>) -> Unit)? = null
        var openUri: ((URI) -> Unit)? = null
        var reopened: (() -> Unit)? = null

        override fun isSupported(action: Desktop.Action): Boolean {
            if (action == failSupportCheck) error("support check failed")
            return action in supported
        }

        override fun onAbout(handler: () -> Unit) {
            if (failAboutRegistration) error("registration failed")
            about = handler
        }

        override fun onPreferences(handler: () -> Unit) {
            preferences = handler
        }

        override fun onOpenUri(handler: (URI) -> Unit) {
            openUri = handler
        }

        override fun onOpenFiles(handler: (List<File>) -> Unit) {
            openFiles = handler
        }

        override fun onReopened(handler: () -> Unit) {
            reopened = handler
        }
    }

    @Test
    fun `about preferences and reopen events reach their respective consumers`() = runTest {
        val registry = FakeRegistry(setOf(Desktop.Action.APP_ABOUT, Desktop.Action.APP_PREFERENCES, Desktop.Action.APP_EVENT_REOPENED))
        val controller = MenuController()
        val commands = mutableListOf<MenuCommand>()
        val collector = launch { controller.commands.collect { commands += it } }
        runCurrent()
        var reopenCount = 0
        installAppEventHandlers(controller, {}, {}, { reopenCount++ }, registry)

        assertNotNull(registry.about).invoke()
        assertNotNull(registry.preferences).invoke()
        assertNotNull(registry.reopened).invoke()
        assertNotNull(registry.reopened).invoke()
        runCurrent()

        assertEquals(listOf(MenuCommand.About, MenuCommand.OpenSettings), commands)
        assertEquals(2, reopenCount)
        collector.cancel()
    }

    @Test
    fun `registration failures do not prevent later handlers from being installed`() {
        val registry = FakeRegistry(
            supported = setOf(Desktop.Action.APP_ABOUT, Desktop.Action.APP_OPEN_URI, Desktop.Action.APP_OPEN_FILE, Desktop.Action.APP_EVENT_REOPENED),
            failSupportCheck = Desktop.Action.APP_PREFERENCES,
            failAboutRegistration = true,
        )
        val opened = mutableListOf<String>()
        val callbacks = mutableListOf<String>()
        install(registry, opened, callbacks)

        assertNull(registry.about)
        assertNull(registry.preferences)
        val callback = "$CUSTOM_URI_SCHEME://oauth?code=c"
        assertNotNull(registry.openUri).invoke(URI(callback))
        assertNotNull(registry.openFiles).invoke(listOf(File("feeds.opml")))
        assertNotNull(registry.reopened)
        assertEquals(listOf(callback), callbacks)
        assertEquals(listOf(File("feeds.opml").absolutePath), opened)
    }

    private fun install(
        registry: AppEventRegistry?,
        opened: MutableList<String>,
        callbacks: MutableList<String> = mutableListOf(),
    ) {
        installAppEventHandlers(
            menuController = MenuController(),
            onOAuthCallback = { callbacks += it },
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
    fun `an open-uri event passes only the custom-scheme callback on`() {
        val registry = FakeRegistry(setOf(Desktop.Action.APP_OPEN_URI))
        val callbacks = mutableListOf<String>()
        install(registry, mutableListOf(), callbacks)

        val handler = assertNotNull(registry.openUri, "the open-uri handler was registered")
        val callback = "$CUSTOM_URI_SCHEME://oauth?code=c"
        handler(URI(callback))
        handler(URI("https://example.com/"))

        assertEquals(listOf(callback), callbacks)
    }

    @Test
    fun `no open-file or open-uri handler is registered where the action is unsupported`() {
        val registry = FakeRegistry(supported = emptySet())

        install(registry, mutableListOf())

        assertNull(registry.about)
        assertNull(registry.preferences)
        assertNull(registry.openFiles)
        assertNull(registry.openUri)
        assertNull(registry.reopened)
    }

    @Test
    fun `no registry registers nothing and does not fail`() {
        install(registry = null, opened = mutableListOf())
    }
}
