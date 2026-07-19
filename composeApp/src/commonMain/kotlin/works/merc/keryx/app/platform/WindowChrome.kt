package works.merc.keryx.app.platform

/**
 * Extra top inset (dp) needed so window content doesn't collide with
 * OS-drawn window controls when the title bar is merged into the content
 * area (macOS only for now; 0 elsewhere). Measured at runtime from the
 * actual OS title bar height rather than hardcoded, since it varies by
 * macOS version/system settings.
 */
expect object WindowChrome {
    var titleBarInsetDp: Float
}
