package works.merc.keryx.app.platform

/** Opens a URL in the user's default browser. */
expect object BrowserOpener {
    fun open(url: String)
}
