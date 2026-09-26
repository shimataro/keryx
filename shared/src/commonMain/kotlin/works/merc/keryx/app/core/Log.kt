package works.merc.keryx.app.core

/**
 * Minimal cross-platform diagnostic logger.
 *
 * This is for *diagnostics only* — user-facing errors still flow through
 * [Result] / [KeryxException] and the notification center. Its purpose is to
 * make otherwise-silent failures (swallowed `runCatching`, background-loop
 * exceptions, keyring/token-store errors) recoverable after release.
 *
 * `expect` because the sink is platform-specific: the desktop actual writes to
 * a rotating file under the app data dir (`logs/`) plus stderr.
 */
expect object Log {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, throwable: Throwable? = null)
    fun error(tag: String, message: String, throwable: Throwable? = null)
}
