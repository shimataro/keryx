package works.merc.keryx.app.platform

import java.awt.Desktop
import java.net.URI

actual object BrowserOpener {
    actual fun open(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() &&
                Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
            ) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        }
        val os = System.getProperty("os.name").lowercase()
        val cmd = when {
            os.contains("mac") -> arrayOf("open", url)
            os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
            else -> arrayOf("xdg-open", url)
        }
        runCatching { ProcessBuilder(*cmd).start() }
    }
}
