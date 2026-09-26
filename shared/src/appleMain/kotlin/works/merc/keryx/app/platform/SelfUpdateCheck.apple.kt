package works.merc.keryx.app.platform

/** Apple builds update through the App Store or Sparkle, never the in-app self-updater. */
actual val selfUpdateCheckSupported: Boolean = false
