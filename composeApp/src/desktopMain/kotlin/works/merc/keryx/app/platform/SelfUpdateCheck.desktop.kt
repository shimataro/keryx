package works.merc.keryx.app.platform

/** Desktop has no app-store update mechanism to defer to, so the in-app check is always offered. */
actual val selfUpdateCheckSupported: Boolean = true
