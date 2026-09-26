package works.merc.keryx.app.sdk

import app.cash.sqldelight.db.SqlDriver
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import works.merc.keryx.app.core.CloudStorageAvailability
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.findDatabaseTooNew
import works.merc.keryx.app.di.applePlatformModule
import works.merc.keryx.app.di.presentationModule
import works.merc.keryx.app.di.sharedModule
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.NewArticleNotifier
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.OAuthCallbackParams
import works.merc.keryx.app.domain.OsNotificationSink
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.parseOAuthUri
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.presentation.home.AddFeedController
import works.merc.keryx.app.presentation.home.HomeViewModel
import kotlin.coroutines.cancellation.CancellationException

/**
 * The native Apple app's one entry point into the shared code: builds the object graph (without
 * exposing Koin to Swift) and hands out the state holders and services a SwiftUI screen observes.
 * One instance per process; see `docs/app-architecture.md`'s "Apple Native Apps (SwiftUI)".
 *
 * Every call that can fail for a reason other than cancellation is declared `@Throws`, so it reaches
 * Swift as a thrown `Error` instead of terminating the process.
 */
class KeryxSdk private constructor(private val koin: Koin) {

    private var homeViewModelCreated = false

    /** The home screen's shared state holder (filter, selection, article list, search, …). */
    val homeViewModel: HomeViewModel get() = koin.get<HomeViewModel>().also { homeViewModelCreated = true }

    val notificationCenter: NotificationCenter get() = koin.get()

    /** Emits the new-articles text for every refresh that found some (also passed to the OS sink). */
    val newArticleNotifier: NewArticleNotifier get() = koin.get()

    val syncRepository: SyncRepository get() = koin.get()

    val settingsRepository: SettingsRepository get() = koin.get()

    val cloudSession: CloudSession get() = koin.get()

    /** Cloud providers configured in this build, in display order. */
    val availableCloudTypes: List<CloudStorageType> get() = CloudStorageAvailability.available

    /** A fresh add-feed state machine, one per add-feed sheet. */
    fun newAddFeedController(): AddFeedController {
        val home = homeViewModel
        return AddFeedController(home::resolvePreview, home::subscribeFeeds)
    }

    /**
     * Routes an OAuth redirect the app received (`keryx://oauth2/callback?…`, e.g. from
     * `onOpenURL` or an `ASWebAuthenticationSession`) back into the waiting connect flow.
     *
     * @return `false` when no connect flow was waiting for it.
     */
    fun handleOAuthRedirect(url: String): Boolean =
        koin.get<MutableSharedFlow<OAuthCallbackParams>>().tryEmit(parseOAuthUri(url))

    /**
     * Shuts the graph down: cancels its background work and closes the database. The instance is
     * unusable afterwards. The app normally never calls this (the graph lives as long as the
     * process); tests and previews do.
     */
    @Throws(CancellationException::class)
    suspend fun close() {
        // Every coroutine that can still be reading the database is stopped *and finished* before
        // the driver closes — closing it under a running query corrupts the driver's own pool.
        if (homeViewModelCreated) homeViewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
        koin.get<CoroutineScope>().coroutineContext.job.cancelAndJoin()
        koin.get<SqlDriver>().close()
        koin.close()
        AppDirs.rootOverride = null
    }

    /** Creates the full-text search index on first run and backfills rows missing from it. */
    @Throws(Exception::class, CancellationException::class)
    suspend fun prepareSearchIndex() {
        koin.get<FtsManager>().ensureIndexed()
    }

    companion object {
        /**
         * Builds the shared object graph and opens the database.
         *
         * @param newArticlesText Localized text of the new-articles OS notification for `count`.
         * @param postOsNotification Posts that notification (message, count); may do nothing.
         * @param dataDirectory Where to keep all app data instead of Application Support — for
         *   previews and tests; `null` in the shipping app.
         * @throws works.merc.keryx.app.data.local.DatabaseTooNewException if a newer build migrated
         *   the database.
         */
        @Throws(Exception::class)
        fun start(
            newArticlesText: (count: Int) -> String,
            postOsNotification: (message: String, count: Int) -> Unit,
            dataDirectory: String?,
        ): KeryxSdk {
            AppDirs.rootOverride = dataDirectory
            val messages = object : NotificationMessages {
                override suspend fun newArticles(count: Int): String = newArticlesText(count)
            }
            val sink = OsNotificationSink { message, count -> postOsNotification(message, count) }
            val koin = koinApplication {
                modules(sharedModule(), presentationModule(), applePlatformModule(messages, sink))
            }.koin
            // Open the database now, so a too-new file is reported here, as a Swift error — unwrapped
            // from Koin's own instance-creation wrapper so the app can recognise it.
            try {
                koin.get<SqlDriver>()
            } catch (e: Exception) {
                throw findDatabaseTooNew(e) ?: e
            }
            return KeryxSdk(koin)
        }
    }
}
