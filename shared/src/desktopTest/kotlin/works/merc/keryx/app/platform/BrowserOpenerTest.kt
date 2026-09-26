package works.merc.keryx.app.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Exercises [browserCommand]'s per-OS command selection directly, independent of the OS the test
 * itself runs on — see that function's own KDoc for why it's pulled out as a pure function.
 */
class BrowserOpenerTest {
    private val urls = listOf(
        "https://example.com/feed?a=1&b=2",
        "mailto:someone@example.com?subject=Hello%20there",
    )

    @Test
    fun macOsUsesOpen() {
        for (url in urls) {
            assertContentEquals(
                arrayOf("open", url),
                browserCommand(url, isMacOs = true, isWindows = false),
                "url=$url",
            )
        }
    }

    @Test
    fun windowsUsesTheUrlProtocolHandler() {
        for (url in urls) {
            assertContentEquals(
                arrayOf("rundll32", "url.dll,FileProtocolHandler", url),
                browserCommand(url, isMacOs = false, isWindows = true),
                "url=$url",
            )
        }
    }

    @Test
    fun anyOtherOsUsesXdgOpen() {
        for (url in urls) {
            assertContentEquals(
                arrayOf("xdg-open", url),
                browserCommand(url, isMacOs = false, isWindows = false),
                "url=$url",
            )
        }
    }

    @Test
    fun theUrlIsPassedAsOneUnmodifiedArgument() {
        val url = "https://example.com/a b?q=x y&r=\"z\""
        for ((mac, win) in listOf(true to false, false to true, false to false)) {
            val command = browserCommand(url, isMacOs = mac, isWindows = win)
            assertContentEquals(arrayOf(url), arrayOf(command.last()), "mac=$mac win=$win")
        }
    }
}
