package works.merc.keryx.app.platform

actual object BrowserOpener {
    /**
     * Opens the specified URL with the OS's default handler for its scheme — the default browser
     * for `http(s)`, the default mail client for `mailto:`.
     *
     * Deliberately does not use AWT's `Desktop` API: `:shared` must never reference AWT/Swing (it is
     * consumed by UIs that have no AWT at all). The per-OS launcher command resolves every scheme
     * through the OS's own registered handler, so `mailto:` needs no special case.
     *
     * @param url The URL to open.
     */
    actual fun open(url: String) {
        runCatching { ProcessBuilder(*browserCommand(url, isMacOs, isWindows)).start() }
    }
}

/**
 * The command line that hands [url] to the OS's default handler for its scheme. Pulled out as a
 * pure function (with the OS flags as parameters) so each OS branch can be unit-tested regardless
 * of the OS the test itself runs on.
 *
 * - macOS: `open` resolves the scheme through Launch Services.
 * - Windows: `url.dll,FileProtocolHandler` resolves it through the registered URL protocol handler
 *   (`HKCR\<scheme>\shell\open\command`), the same lookup `ShellExecute` uses.
 * - Anything else (Linux/BSD): `xdg-open` resolves it through the desktop's
 *   `x-scheme-handler/<scheme>` MIME association.
 */
internal fun browserCommand(url: String, isMacOs: Boolean, isWindows: Boolean): Array<String> = when {
    isMacOs -> arrayOf("open", url)
    isWindows -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
    else -> arrayOf("xdg-open", url)
}
