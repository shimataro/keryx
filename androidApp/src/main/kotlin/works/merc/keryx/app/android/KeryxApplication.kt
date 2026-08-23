package works.merc.keryx.app.android

import android.app.Application
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform
import works.merc.keryx.app.background.startBackgroundRefresh
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.di.appModule
import works.merc.keryx.app.di.configureImageLoader
import works.merc.keryx.app.di.platformModule
import works.merc.keryx.app.platform.AndroidAppContext
import works.merc.keryx.app.platform.AppDirs

/**
 * Android's entry point, equivalent to desktop's `main.kt` — but only the parts that don't belong
 * to a single [android.app.Activity]: process-wide setup that must run exactly once, before any
 * `Activity` or Koin-resolved dependency is touched.
 *
 * [AndroidAppContext.init] runs first and unconditionally: it is what lets every `expect object`
 * needing a `Context` (`AppDirs`, `BrowserOpener`, `ClipboardEntries`) and `DatabaseDriverFactory`
 * resolve one without changing their commonMain signature (see `AndroidAppContext`'s own KDoc).
 */
class KeryxApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        AndroidAppContext.init(this)

        startKoin { modules(appModule, platformModule) }
        val koin = KoinPlatform.getKoin()

        configureImageLoader(koin.get<HttpClient>(), AppDirs.cacheDir())

        // Desktop's main.kt does this with runBlocking before application {} — acceptable there
        // since it only delays showing the first window by a trivial amount. Blocking
        // Application.onCreate the same way would delay every cold start instead, so this runs as
        // fire-and-forget on the shared app-scope CoroutineScope (registered in appModule).
        // ensureIndexed() only backfills newly-unindexed rows, so a search performed in the brief
        // window before it completes just returns fewer/no hits rather than failing outright.
        koin.get<CoroutineScope>().launch {
            koin.get<FtsManager>().ensureIndexed()
        }

        // Keeps WorkManager's periodic feed-refresh job in sync with the refresh-interval
        // setting for the rest of the process's life — see that function's own KDoc for why this
        // belongs in Application.onCreate rather than MainActivity.
        startBackgroundRefresh(koin)
    }
}
