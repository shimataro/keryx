package works.merc.keryx.app.platform

import java.awt.Desktop
import java.net.URI

actual object BrowserOpener {
    /**
     * Opens the specified URL using the platform's default browser.
     *
     * @param url The URL to open.
     */
    actual fun open(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() &&
                Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
            ) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        }
        val cmd = when {
            isMacOs -> arrayOf("open", url)
            isWindows -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
            else -> arrayOf("xdg-open", url)
        }
        runCatching { ProcessBuilder(*cmd).start() }
    }
}
