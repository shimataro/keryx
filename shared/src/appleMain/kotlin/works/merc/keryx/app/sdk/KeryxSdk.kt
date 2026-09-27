package works.merc.keryx.app.sdk

import app.cash.sqldelight.db.SqlDriver
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
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
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.domain.AuthorizationLauncher
import works.merc.keryx.app.domain.CloudConnectionService
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.DefaultAuthorizationLauncher
import works.merc.keryx.app.domain.NewArticleNotifier
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.OAuthCallbackParams
import works.merc.keryx.app.domain.OsNotificationSink
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.parseOAuthUri
import works.merc.keryx.app.domain.schemeOf
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
     * @return `false` when no connect flow was waiting for it; the redirect is then dropped, not
     *   kept for a flow that starts later.
     */
    fun handleOAuthRedirect(url: String): Boolean {
        val callbacks = oauthCallbacks
        // With no subscriber a SharedFlow drops the value yet `tryEmit` still reports `true`, so the
        // subscriber check is what makes the result mean "delivered".
        if (callbacks.subscriptionCount.value == 0) return false
        return callbacks.tryEmit(parseOAuthUri(url))
    }

    /** The callback flow a connect flow collects while it waits for its redirect. */
    internal val oauthCallbacks: MutableSharedFlow<OAuthCallbackParams> get() = koin.get()

    /**
     * Shuts the graph down: cancels its background work, persists pending device-local settings,
     * and closes the database and the HTTP client. The instance is unusable afterwards. The app normally never calls this (the graph lives as long as the
     * process); tests and previews do.
     */
    @Throws(CancellationException::class)
    suspend fun close() {
        // Every coroutine that can still be reading the database is stopped *and finished* before
        // the driver closes — closing it under a running query corrupts the driver's own pool.
        // The app scope also runs prepareSearchIndex*()'s FTS work, so this waits for (or cancels)
        // that too.
        if (homeViewModelCreated) homeViewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
        koin.get<CoroutineScope>().coroutineContext.job.cancelAndJoin()
        // SettingsRepository keeps its own writer scope; flush it and stop it before AppDirs is
        // reset below, so no late write lands outside this instance's data directory.
        koin.get<SettingsRepository>().close()
        koin.get<SqlDriver>().close()
        koin.get<HttpClient>().close()
        koin.close()
        AppDirs.rootOverride = null
    }

    /**
     * Creates the full-text search index on first run and backfills every row missing from it — an
     * `O(articles)` scan. Call once per foreground app launch. Runs on the app's background scope,
     * so a main-actor caller only awaits it.
     */
    @Throws(Exception::class, CancellationException::class)
    suspend fun prepareSearchIndex() {
        koin.get<CoroutineScope>().async { koin.get<FtsManager>().ensureIndexed() }.await()
    }

    /**
     * Cheap counterpart to [prepareSearchIndex] for a call repeated on every process start (e.g. a
     * background wake): it only creates and backfills the index when the table does not exist yet,
     * and is a single lookup otherwise. Runs on the app's background scope, like [prepareSearchIndex].
     */
    @Throws(Exception::class, CancellationException::class)
    suspend fun prepareSearchIndexIfAbsent() {
        koin.get<CoroutineScope>().async { koin.get<FtsManager>().ensureIndexedIfTableAbsent() }.await()
    }

    /**
     * Records a successful authorization: saves [tokens], selects [type] as the active provider,
     * and flushes local settings before any sync may start — see [CloudConnectionService.completeConnect].
     * Runs on the app's background scope, like [prepareSearchIndex]: [CloudConnectionService]'s own
     * KDoc requires its caller to run off the main thread (it may block on the Keychain), and unlike
     * the Compose UI — which wraps every call in its own `withContext(dispatcher)` — a Swift caller
     * has no equivalent dispatcher to switch onto, so the SDK does it here instead of leaving Swift
     * to invoke this straight from its `@MainActor` call site.
     */
    @Throws(Exception::class, CancellationException::class)
    suspend fun completeConnect(type: CloudStorageType, tokens: OAuthTokens) {
        val service = koin.get<CloudConnectionService>()
        koin.get<CoroutineScope>().async { service.completeConnect(type, tokens) }.await()
    }

    /**
     * Disconnects [type] and clears everything a subsequent connect must not inherit — see
     * [CloudConnectionService.tearDown]. Runs on the app's background scope; see [completeConnect]'s
     * own KDoc for why.
     */
    @Throws(Exception::class, CancellationException::class)
    suspend fun tearDownConnection(type: CloudStorageType) {
        val service = koin.get<CloudConnectionService>()
        koin.get<CoroutineScope>().async { service.tearDown(type) }.await()
    }

    companion object {
        /**
         * Builds the shared object graph and opens the database.
         *
         * @param newArticlesText Localized text of the new-articles OS notification for `count`.
         * @param postOsNotification Posts that notification (message, count); may do nothing.
         * @param dataDirectory Where to keep all app data instead of Application Support — for
         *   previews and tests; `null` in the shipping app.
         * @param openAuthorization Opens a cloud provider's OAuth authorize URL for the user —
         *   the shipping app hands both arguments to an `ASWebAuthenticationSession`
         *   (`callbackScheme` is that session's `callbackURLScheme`, derived from the connect
         *   flow's own redirect URI, e.g. `keryx` or a Google reversed-client-id scheme). `null`
         *   (the default) falls back to opening the system browser, which previews and tests that
         *   never actually connect a provider can safely ignore.
         * @param useDataProtectionKeychain Whether cloud tokens go in the Data Protection Keychain
         *   rather than the ordinary login Keychain — see `data/cloud/KeychainTokenStorage.kt`'s own
         *   doc for why. The shipping app passes `true`; it needs the `keychain-access-groups`
         *   entitlement and a real code signature, so this defaults to `false` for tests/previews
         *   that build with an ad-hoc or no signature at all.
         * @throws works.merc.keryx.app.data.local.DatabaseTooNewException if a newer build migrated
         *   the database.
         */
        @Throws(Exception::class)
        fun start(
            newArticlesText: (count: Int) -> String,
            postOsNotification: (message: String, count: Int) -> Unit,
            dataDirectory: String?,
            openAuthorization: ((url: String, callbackScheme: String) -> Unit)? = null,
            useDataProtectionKeychain: Boolean = false,
        ): KeryxSdk {
            AppDirs.rootOverride = dataDirectory
            val messages = object : NotificationMessages {
                override suspend fun newArticles(count: Int): String = newArticlesText(count)
            }
            val sink = OsNotificationSink { message, count -> postOsNotification(message, count) }
            val authorizationLauncher = openAuthorization?.let { open ->
                AuthorizationLauncher { authorizeUrl, redirectUri ->
                    open(authorizeUrl, schemeOf(redirectUri).orEmpty())
                }
            } ?: DefaultAuthorizationLauncher
            val koin = koinApplication {
                modules(
                    sharedModule(),
                    presentationModule(),
                    applePlatformModule(messages, sink, authorizationLauncher, useDataProtectionKeychain),
                )
            }.koin
            // Open the database now, so a too-new file is reported here, as a Swift error — unwrapped
            // from Koin's own instance-creation wrapper so the app can recognise it. A failed start
            // releases the graph and the directory override, so the next start begins clean.
            try {
                koin.get<SqlDriver>()
            } catch (e: Exception) {
                koin.close()
                AppDirs.rootOverride = null
                throw findDatabaseTooNew(e) ?: e
            }
            return KeryxSdk(koin)
        }
    }
}
