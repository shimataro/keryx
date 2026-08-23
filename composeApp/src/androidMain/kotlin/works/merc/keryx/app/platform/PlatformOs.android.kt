package works.merc.keryx.app.platform

actual val isMacOs: Boolean = false

/** Android's primary pointer is touch. */
actual val isTouchPrimary: Boolean = true

/** Android has no application menu bar equivalent; Settings/About need their own in-pane entry
 * points (see the commonMain `expect`'s KDoc). */
actual val hasNativeAppMenu: Boolean = false
