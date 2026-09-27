package works.merc.keryx.app.platform

import android.content.Context

/**
 * Holds the process-wide application [Context].
 *
 * Several `expect object`s (`AppDirs`, `BrowserOpener`, `ClipboardEntries`) and
 * `DatabaseDriverFactory` need a `Context` but cannot take one as a constructor parameter without
 * changing their commonMain signature — which would force an equivalent, pointless parameter on
 * every other platform. Instead, `KeryxApplication.onCreate()` (in the `:androidApp` module)
 * calls [init] before starting Koin or creating any `Activity`, so every actual that reads
 * [application] below always sees it already set.
 */
object AndroidAppContext {
    private var _application: Context? = null

    /** The process-wide application [Context], set by [init]. */
    val application: Context
        get() = checkNotNull(_application) {
            "AndroidAppContext.init() was not called before this was read — it must run first " +
                "in Application.onCreate(), before Koin or any expect/actual that needs a Context."
        }

    /** Records [context]'s application context. Call once, from `Application.onCreate()`. */
    fun init(context: Context) {
        _application = context.applicationContext
    }
}
