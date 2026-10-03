package works.merc.keryx.app.platform

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Holds the process-wide application [Context], plus a weak reference to the currently resumed
 * [Activity].
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

    @Volatile
    private var resumed: WeakReference<Activity>? = null

    /** The process-wide application [Context], set by [init]. */
    val application: Context
        get() = checkNotNull(_application) {
            "AndroidAppContext.init() was not called before this was read — it must run first " +
                "in Application.onCreate(), before Koin or any expect/actual that needs a Context."
        }

    /**
     * The [Activity] currently in the resumed state, or `null` while none is (the app is in the
     * background, or only a `WorkManager` worker is running). Held weakly so it never keeps a
     * destroyed activity alive. Lets a launch such as `BrowserOpener`'s Custom Tab start from an
     * activity — joining its task, so Back returns to the app — instead of the application context.
     */
    val resumedActivity: Activity?
        get() = resumed?.get()

    /**
     * Records [application]'s context and starts tracking its resumed [Activity]. Call once, from
     * `Application.onCreate()`.
     */
    fun init(application: Application) {
        _application = application.applicationContext
        application.registerActivityLifecycleCallbacks(ResumedActivityTracker)
    }

    private object ResumedActivityTracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumed = WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            // Only clear when the paused activity is still the recorded one: another activity's
            // onResume can run before this one's onPause is delivered in some transitions.
            if (resumed?.get() === activity) resumed = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
