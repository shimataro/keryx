package works.merc.keryx.app.sdk

import app.cash.sqldelight.db.SqlDriver
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.CloudStorageAvailability
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.findDatabaseTooNew
import works.merc.keryx.app.di.applePlatformModule
import works.merc.keryx.app.di.presentationModule
import works.merc.keryx.app.di.sharedModule
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.domain.AuthorizationLauncher
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.CloudConnectionService
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.DefaultAuthorizationLauncher
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.NewArticleNotifier
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.OAuthCallbackParams
import works.merc.keryx.app.domain.OsNotificationSink
import works.merc.keryx.app.domain.RefreshCycleRunner
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncTrigger
import works.merc.keryx.app.domain.backgroundUpdateLoop
import works.merc.keryx.app.domain.parseOAuthUri
import works.merc.keryx.app.domain.runStartupMaintenance
import works.merc.keryx.app.domain.schemeOf
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.presentation.home.AddFeedController
import works.merc.keryx.app.presentation.home.HomeViewModel
import works.merc.keryx.app.presentation.home.NotificationAlerts
import works.merc.keryx.app.presentation.menu.MenuUiState
import works.merc.keryx.app.presentation.menu.computeMenuUiState
import works.merc.keryx.app.presentation.settings.CloudSyncController
import works.merc.keryx.app.presentation.settings.OpmlTransfer
import works.merc.keryx.app.presentation.settings.OpmlTransferController
import works.merc.keryx.app.presentation.settings.requestOpenedOpmlImport
import works.merc.keryx.app.presentation.settings.PreferencesController
import works.merc.keryx.app.presentation.setup.SetupController
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

    /** Test-only handle on the feed subscriptions, which no screen reaches through this class. */
    internal val feedRepository: FeedRepository get() = koin.get()

    val cloudSession: CloudSession get() = koin.get()

    /** Cloud providers configured in this build, in display order. */
    val availableCloudTypes: List<CloudStorageType> get() = CloudStorageAvailability.available

    private var setupControllerCreated = false

    /** The setup/onboarding screen's shared state holder: local-only vs. connecting a provider. */
    val setupController: SetupController get() = koin.get<SetupController>().also { setupControllerCreated = true }

    private var cloudSyncControllerCreated = false

    /** The settings screen's cloud-sync state and actions (connect/disconnect/switch/reconnect/…). */
    val cloudSyncController: CloudSyncController
        get() = koin.get<CloudSyncController>().also { cloudSyncControllerCreated = true }

    /** Typed setters over `LocalSettings`/`global_settings`. */
    val preferences: PreferencesController get() = koin.get()

    /** Builds/parses the OPML document itself; picking a file to write/read stays with Swift. */
    val opml: OpmlTransfer get() = koin.get()

    /**
     * The one OPML busy/result/request state every route shares — the Data settings tab, the File
     * menu (which only [OpmlTransferController.request]s, then shows Settings ▸ Data), and an opened
     * `.opml` file — mirrored in Swift by `OpmlTransferObservable`.
     */
    val opmlController: OpmlTransferController get() = koin.get()

    private var notificationAlertsCreated = false

    /** Which warning/error still needs announcing in a transient surface with no queue of its own
     * (Android's foreground Snackbar equivalent — a future SwiftUI surface could use the same
     * signal instead of reimplementing the recurrence-dedup rule). */
    val notificationAlerts: NotificationAlerts
        get() = koin.get<NotificationAlerts>().also { notificationAlertsCreated = true }

    /**
     * Enabled/checked state for a menu/`Commands` item — see
     * [works.merc.keryx.app.presentation.menu.computeMenuUiState]'s own doc for what each
     * parameter gates. Every parameter is explicit (no defaults), so a Swift caller can't silently
     * leave one at a value that enables an item it shouldn't.
     */
    fun menuState(
        onHome: Boolean,
        hasSelectedArticle: Boolean,
        selectedArticleHasUrl: Boolean,
        selectedArticleCanOpenInBrowser: Boolean,
        canSyncNow: Boolean,
        searchActive: Boolean,
        unreadOnly: Boolean,
        opmlBusy: Boolean,
        hasSelectedFeed: Boolean,
        feedListKeysActive: Boolean,
        hasRenamableSelection: Boolean,
        selectedFeedHasSiteUrl: Boolean,
        selectedFeedSiteCanOpenInBrowser: Boolean,
    ): MenuUiState = computeMenuUiState(
        onHome = onHome,
        hasSelectedArticle = hasSelectedArticle,
        selectedArticleHasUrl = selectedArticleHasUrl,
        selectedArticleCanOpenInBrowser = selectedArticleCanOpenInBrowser,
        activity = homeViewModel.activity.value,
        canSyncNow = canSyncNow,
        searchActive = searchActive,
        unreadOnly = unreadOnly,
        opmlBusy = opmlBusy,
        hasSelectedFeed = hasSelectedFeed,
        feedListKeysActive = feedListKeysActive,
        hasRenamableSelection = hasRenamableSelection,
        selectedFeedHasSiteUrl = selectedFeedHasSiteUrl,
        selectedFeedSiteCanOpenInBrowser = selectedFeedSiteCanOpenInBrowser,
    )

    /** A fresh add-feed state machine, one per add-feed sheet. */
    fun newAddFeedController(): AddFeedController {
        val home = homeViewModel
        return AddFeedController(home::resolvePreview, home::subscribeFeeds)
    }

    private var startupJob: Job? = null
    private var refreshLoopJob: Job? = null

    /** Whether the startup maintenance sequence is currently running — for tests. */
    internal val isStartupMaintenanceActive: Boolean get() = startupJob?.isActive == true

    /** Whether the periodic refresh loop is currently running — for tests. */
    internal val isRefreshLoopActive: Boolean get() = refreshLoopJob?.isActive == true

    /**
     * Starts the startup maintenance sequence ([runStartupMaintenance]: cache cleanup, initial
     * sync, feed refresh, update check, FTS heal) and the periodic background-refresh loop
     * ([backgroundUpdateLoop]) on the SDK's own background scope. Call whenever the app becomes
     * active: idempotent, so a repeated call (e.g. from a view that appears more than once) neither
     * reruns the startup sequence nor starts a second overlapping loop — but after [stopRefreshLoop]
     * it restarts the loop, and the startup sequence too if that call interrupted it (a sequence
     * that completed is never rerun). Neither is awaited — like desktop's `main.kt`, both keep
     * running independently for as long as this instance lives.
     */
    @Throws(Exception::class, CancellationException::class)
    fun startMaintenance() {
        val scope = koin.get<CoroutineScope>()
        val previous = startupJob
        if (previous == null || previous.isCancelled) {
            // Join the interrupted run first, so a quick reactivation never overlaps it.
            startupJob = scope.launch {
                previous?.cancelAndJoin()
                runStartupMaintenance(koin)
            }
        }
        if (!isRefreshLoopActive) {
            refreshLoopJob = scope.launch { backgroundUpdateLoop(koin) }
        }
    }

    /**
     * Stops the periodic refresh loop and any unfinished startup sequence (iOS, when the app leaves the
     * foreground): the OS can wake the suspended process for a `BGAppRefreshTask`, and a loop timer
     * expiring — or the startup sequence still running — would then spend the short background slot on
     * work [runBackgroundRefresh] deliberately skips. The next [startMaintenance] starts the loop again,
     * with a fresh interval, and reruns the startup sequence if it was interrupted. Does nothing for
     * whatever is not running.
     */
    fun stopRefreshLoop() {
        refreshLoopJob?.cancel()
        refreshLoopJob = null
        startupJob?.takeIf { it.isActive }?.cancel()
    }

    /**
     * One background-wake cycle for the OS's periodic refresh (iOS `BGAppRefreshTask`): refreshes
     * every feed, posts the new-article notification, and syncs when a provider is connected
     * ([RefreshCycleRunner.runIfIdle], so it is skipped while the foreground loop already runs a
     * cycle). Unlike [startMaintenance] it neither runs the startup sequence nor rebuilds the FTS
     * index — a background slot is far too short for either.
     *
     * Does nothing before setup completes (like `FeedRefreshWorker`), and then does not flush
     * either: the settings file's existence *is* the setup-complete marker. Cancelling the caller
     * cancels the work and waits for it to finish, so an expired background slot stops it before
     * this throws.
     *
     * @return The total unread count afterwards, for the app icon badge.
     */
    @Throws(Exception::class, CancellationException::class)
    suspend fun runBackgroundRefresh(): Long {
        val settings = koin.get<SettingsRepository>()
        val articles = koin.get<ArticleRepository>()
        if (!settings.isSetupComplete()) return articles.watchUnreadCount().first()
        val work = koin.get<CoroutineScope>().async {
            koin.get<FtsManager>().ensureIndexedIfTableAbsent()
            koin.get<RefreshCycleRunner>().runIfIdle(ArticleFilter.All, SyncTrigger.AUTOMATIC)
            settings.flush()
            articles.watchUnreadCount().first()
        }
        try {
            return work.await()
        } catch (e: CancellationException) {
            // `work` runs on the SDK's scope, not as a child of the caller, so cancelling the caller
            // does not wait for it. Join it here: iOS treats the background task as finished once the
            // caller returns, and would suspend the app with refresh or sync cleanup still running.
            withContext(NonCancellable) { work.cancelAndJoin() }
            throw e
        }
    }

    /**
     * Asks Settings ▸ Data to import an OPML file the app was opened with (mirrors desktop's/Android's
     * own ".opml file association" handling — see [requestOpenedOpmlImport]); `null` [xml] means
     * the file could not be read, which the Data tab then shows as an import failure. The SwiftUI
     * app's Home shows Settings on the Data tab for the waiting request, so a file opened during
     * Setup is imported once Setup is done.
     */
    fun importOpenedOpml(xml: String?) {
        requestOpenedOpmlImport(koin, xml)
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
        if (setupControllerCreated) setupController.viewModelScope.coroutineContext.job.cancelAndJoin()
        // HomeViewModel resolves CloudSyncController itself (as its ManualSync), so creating either
        // one creates the controller.
        if (cloudSyncControllerCreated || homeViewModelCreated) {
            cloudSyncController.viewModelScope.coroutineContext.job.cancelAndJoin()
        }
        if (notificationAlertsCreated) notificationAlerts.viewModelScope.coroutineContext.job.cancelAndJoin()
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
         *   that build with an ad-hoc or no signature at all. Never leave it `false` in a shipping
         *   build: the service and account match the Compose desktop build's, so the Data Protection
         *   Keychain is the only thing keeping the two apps off each other's items (one's
         *   disconnect would revoke the other's refresh token).
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
        ): KeryxSdk = startWithModules(
            newArticlesText, postOsNotification, dataDirectory, openAuthorization, useDataProtectionKeychain,
            extraModules = emptyList(),
        )

        /**
         * [start] with [extraModules] registered after the production modules, so they override its
         * bindings — a test seam (e.g. a `MockEngine` `HttpClient`). `internal`, so Swift never sees it.
         */
        internal fun startWithModules(
            newArticlesText: (count: Int) -> String,
            postOsNotification: (message: String, count: Int) -> Unit,
            dataDirectory: String?,
            openAuthorization: ((url: String, callbackScheme: String) -> Unit)? = null,
            useDataProtectionKeychain: Boolean = false,
            extraModules: List<Module>,
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
                // Last, so a test's module overrides the production binding of the same type.
                modules(extraModules)
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
