package works.merc.keryx.app.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `webViewDataDirectory` joins the app's cache directory with the reader WebView's own data
 * subdirectory. Setting this explicitly is what makes WebView2 avoid trying (and failing, with
 * Access Denied) to create its data folder next to the host executable.
 */
class ArticleReaderDataDirectoryTest {
    @Test
    fun joinsWithoutASeparator() {
        assertEquals("/cache/webview", webViewDataDirectory("/cache"))
    }

    @Test
    fun trimsATrailingForwardSlash() {
        assertEquals("/cache/webview", webViewDataDirectory("/cache/"))
    }

    @Test
    fun trimsATrailingBackslash() {
        assertEquals("C:\\Users\\me\\AppData\\Local\\Keryx\\Cache/webview", webViewDataDirectory("C:\\Users\\me\\AppData\\Local\\Keryx\\Cache\\"))
    }

    @Test
    fun leavesAWindowsPathWithoutATrailingSeparatorIntact() {
        assertEquals("C:\\Users\\me\\AppData\\Local\\Keryx\\Cache/webview", webViewDataDirectory("C:\\Users\\me\\AppData\\Local\\Keryx\\Cache"))
    }
}
