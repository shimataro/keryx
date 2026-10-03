package works.merc.keryx.app.sdk

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.dsl.module
import platform.posix.usleep
import works.merc.keryx.app.core.DB_FILE_NAME
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.local.DatabaseTooNewException
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.OAuthCallbackParams
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.platform.FileSystemExtras
import works.merc.keryx.app.platform.RawSqliteConnection
import works.merc.keryx.app.tempFilePath
import works.merc.keryx.app.presentation.settings.OpmlRequest
import works.merc.keryx.app.presentation.settings.OpmlResult
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FEED_URL = "https://example.com/feed.xml"

@OptIn(ExperimentalCoroutinesApi::class)
class KeryxSdkTest {

    private val dir = tempFilePath("keryx-sdk-", "")

    // HomeViewModel's viewModelScope runs on Dispatchers.Main, which has no run loop in a test.
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        AppDirs.rootOverride = null
        FileSystemExtras.deleteRecursively(dir)
    }

    private fun start() = KeryxSdk.start(
        newArticlesText = { count -> "$count new" },
        postOsNotification = { _, _ -> },
        dataDirectory = dir,
    )

    private var serverItems = 1

    /** Runs inside the SDK's OS-notification sink, on whichever thread the cycle posted from. */
    private var onNotify: () -> Unit = {}

    /** When set, the feed server signals [feedRequests] once per request and then holds every response until it completes. */
    private var feedGate: CompletableDeferred<Unit>? = null
    private val feedRequests = Channel<Unit>(Channel.UNLIMITED)

    private fun rss(articleCount: Int) = buildString {
        append("""<?xml version="1.0"?><rss version="2.0"><channel><title>Feed</title><link>https://example.com</link>""")
        repeat(articleCount) { append("<item><title>Post $it</title><link>https://example.com/$it</link><guid>g$it</guid></item>") }
        append("</channel></rss>")
    }

    /** An SDK whose HTTP client serves [serverItems] articles at [url] instead of using the network. */
    private fun startWithFeedServer(url: String): KeryxSdk {
        val client = HttpClient(
            MockEngine { request ->
                check(request.url.toString() == url) { "unexpected request ${request.url}" }
                feedGate?.let {
                    feedRequests.trySend(Unit)
                    it.await()
                }
                respond(rss(serverItems), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/rss+xml"))
            },
        ) {
            followRedirects = false
            expectSuccess = false
        }
        return KeryxSdk.startWithModules(
            newArticlesText = { count -> "$count new" },
            postOsNotification = { _, _ -> onNotify() },
            dataDirectory = dir,
            extraModules = listOf(module { single<HttpClient> { client } }),
        )
    }

    @Test
    fun startKeepsEverythingUnderTheGivenDataDirectory() = runTest {
        val sdk = start()

        assertEquals(dir, AppDirs.appDataDir())
        val dbPath = FileIO.join(dir, DB_FILE_NAME)
        assertEquals(KeryxDatabase.Schema.version, RawSqliteConnection.userVersionOf(dbPath))
        try {
            sdk.prepareSearchIndex()
            assertTrue(sdk.homeViewModel.feeds.value.isEmpty())
            assertTrue(sdk.newAddFeedController().state.value.url.isEmpty())
        } finally {
            sdk.close()
        }
    }

    @Test
    fun theSdkCanBeStartedAgainAfterClose() = runTest {
        start().close()
        val again = start()
        try {
            assertTrue(again.homeViewModel.feeds.value.isEmpty())
        } finally {
            again.close()
        }
    }

    @Test
    fun aDatabaseFromANewerBuildIsReportedByStart() {
        FileIO.writeBytes(FileIO.join(dir, "placeholder"), ByteArray(0))
        RawSqliteConnection.open(FileIO.join(dir, DB_FILE_NAME), create = true).use { it.exec("PRAGMA user_version = 99") }

        assertFailsWith<DatabaseTooNewException> { start() }
    }

    @Test
    fun aFailedStartReleasesTheDirectoryOverrideAndAllowsAnotherStart() = runTest {
        val dbPath = FileIO.join(dir, DB_FILE_NAME)
        FileIO.writeBytes(FileIO.join(dir, "placeholder"), ByteArray(0))
        RawSqliteConnection.open(dbPath, create = true).use { it.exec("PRAGMA user_version = 99") }
        assertFailsWith<DatabaseTooNewException> { start() }
        assertNull(AppDirs.rootOverride)

        FileIO.delete(dbPath)
        val again = start()
        try {
            assertEquals(dir, AppDirs.appDataDir())
            assertEquals(KeryxDatabase.Schema.version, RawSqliteConnection.userVersionOf(dbPath))
            again.prepareSearchIndexIfAbsent()
        } finally {
            again.close()
        }
        assertNull(AppDirs.rootOverride)
    }

    @Test
    fun anOAuthRedirectWithNobodyWaitingIsReportedUndeliveredAndDropped() = runTest {
        val sdk = start()
        try {
            assertFalse(sdk.handleOAuthRedirect("keryx://oauth2/callback?code=early&state=s"))

            val received = mutableListOf<OAuthCallbackParams>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                sdk.oauthCallbacks.collect { received += it }
            }
            assertTrue(received.isEmpty())
        } finally {
            sdk.close()
        }
    }

    @Test
    fun anOAuthRedirectReachesAWaitingConnectFlow() = runTest {
        val sdk = start()
        try {
            val received = mutableListOf<OAuthCallbackParams>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                sdk.oauthCallbacks.collect { received += it }
            }

            assertTrue(sdk.handleOAuthRedirect("keryx://oauth2/callback?code=abc&state=xyz"))
            assertEquals(listOf("abc"), received.map { it.code })
            assertEquals("xyz", received.single().state)
            collector.cancel()
        } finally {
            sdk.close()
        }
    }

    @Test
    fun backgroundRefreshBeforeSetupDoesNothingAndLeavesSetupIncomplete() = runTest {
        val sdk = start()
        try {
            assertEquals(0L, sdk.runBackgroundRefresh())
            assertFalse(sdk.settingsRepository.isSetupComplete())
        } finally {
            sdk.close()
        }
    }

    @Test
    fun backgroundRefreshAfterSetupRunsACycleAndReturnsTheUnreadCount() = runBlocking {
        val sdk = startWithFeedServer(FEED_URL)
        try {
            sdk.settingsRepository.flush()
            assertTrue(sdk.settingsRepository.isSetupComplete())
            // The app creates the search index at launch; subscribing indexes into it.
            sdk.prepareSearchIndex()
            assertTrue(sdk.feedRepository.subscribeFeed(FEED_URL) is Result.Ok)
            assertEquals(1L, sdk.runBackgroundRefresh())
            // A skipped cycle would leave the count at the subscribe-time value; a second item
            // served on the next fetch is only counted if the cycle actually fetched.
            serverItems = 2
            assertEquals(2L, sdk.runBackgroundRefresh())
        } finally {
            sdk.close()
        }
    }

    @Test
    fun cancellingBackgroundRefreshReturnsOnlyAfterTheWorkHasStopped() = runBlocking {
        val sdk = startWithFeedServer(FEED_URL)
        try {
            sdk.settingsRepository.flush()
            sdk.prepareSearchIndex()
            assertTrue(sdk.feedRepository.subscribeFeed(FEED_URL) is Result.Ok)

            // The refresh finds a new article, so the cycle posts the OS notification — a plain,
            // non-cancellable call inside the work: the one place it can be caught mid-flight.
            val notifying = CompletableDeferred<Unit>()
            var notified = false
            onNotify = {
                notifying.complete(Unit)
                usleep(300_000u)
                notified = true
            }
            serverItems = 2
            val caller = launch(Dispatchers.Default) { sdk.runBackgroundRefresh() }
            notifying.await()
            caller.cancelAndJoin()

            // Joined: the work has run past the blocking call, not merely been asked to stop.
            assertTrue(notified)
            assertFalse(sdk.homeViewModel.activity.value.refreshCycleRunning)
        } finally {
            onNotify = {}
            sdk.close()
        }
    }

    @Test
    fun theRefreshLoopStopsOnRequestAndRestartsWithTheNextStartMaintenance() = runBlocking {
        val sdk = start()
        try {
            assertFalse(sdk.isRefreshLoopActive)
            sdk.startMaintenance()
            assertTrue(sdk.isRefreshLoopActive)
            sdk.startMaintenance() // idempotent while running
            assertTrue(sdk.isRefreshLoopActive)

            sdk.stopRefreshLoop()
            assertFalse(sdk.isRefreshLoopActive)
            sdk.stopRefreshLoop() // nothing to stop is fine
            assertFalse(sdk.isRefreshLoopActive)

            sdk.startMaintenance()
            assertTrue(sdk.isRefreshLoopActive)
        } finally {
            sdk.close()
        }
    }

    @Test
    fun anInterruptedStartupSequenceRerunsOnTheNextStartMaintenance() = runBlocking {
        val sdk = startWithFeedServer(FEED_URL)
        try {
            sdk.settingsRepository.flush()
            sdk.prepareSearchIndex()
            assertTrue(sdk.feedRepository.subscribeFeed(FEED_URL) is Result.Ok)

            // The startup sequence's refresh stage is held at the server, so it is still running.
            val gate = CompletableDeferred<Unit>()
            feedGate = gate
            sdk.startMaintenance()
            withTimeout(30_000) { feedRequests.receive() }
            // Exactly one request so far, so the next receive can only be the replacement run's.
            assertTrue(feedRequests.tryReceive().isFailure)
            assertTrue(sdk.isStartupMaintenanceActive)

            sdk.stopRefreshLoop()
            assertFalse(sdk.isStartupMaintenanceActive)

            sdk.startMaintenance()
            // The replacement run's own request: it got past joining the interrupted run and into
            // runStartupMaintenance, not merely launched.
            withTimeout(30_000) { feedRequests.receive() }
            assertTrue(sdk.isStartupMaintenanceActive)
            gate.complete(Unit)
            Unit
        } finally {
            feedGate?.complete(Unit)
            sdk.close()
        }
    }

    @Test
    fun aCompletedStartupSequenceIsNotRerunAfterTheLoopRestarts() = runBlocking {
        val sdk = startWithFeedServer(FEED_URL)
        try {
            sdk.settingsRepository.flush()
            sdk.prepareSearchIndex()
            assertTrue(sdk.feedRepository.subscribeFeed(FEED_URL) is Result.Ok)

            sdk.startMaintenance()
            withTimeout(30_000) { while (sdk.isStartupMaintenanceActive) delay(10) }

            sdk.stopRefreshLoop()
            sdk.startMaintenance()
            assertFalse(sdk.isStartupMaintenanceActive)
            assertTrue(sdk.isRefreshLoopActive)
        } finally {
            sdk.close()
        }
    }

    @Test
    fun theSharedControllersAreReachableAndStartInTheirLocalOnlyState() = runTest {
        val sdk = start()
        try {
            assertNull(sdk.cloudSyncController.connectedType.value)
            assertEquals(sdk.availableCloudTypes, sdk.setupController.availableCloudTypes)
            assertTrue(sdk.preferences.localSettings.value.notificationEnabled)
            assertNull(sdk.notificationAlerts.alertToSurface.value)
        } finally {
            sdk.close()
        }
    }

    @Test
    fun opmlExportsAndImportsTheSameEmptyDocumentThroughTheSdk() = runTest {
        val sdk = start()
        try {
            val exported = sdk.opml.exportOpml()
            val outcome = sdk.opml.importOpml(exported)
            assertEquals(0, outcome.added)
            assertEquals(0, outcome.failed)
        } finally {
            sdk.close()
        }
    }

    @Test
    fun menuStateGatesEverythingOnHome() = runTest {
        val sdk = start()
        try {
            val onHome = sdk.menuState(
                onHome = true,
                hasSelectedArticle = false,
                selectedArticleHasUrl = false,
                selectedArticleCanOpenInBrowser = false,
                canSyncNow = false,
                searchActive = false,
                unreadOnly = false,
                opmlBusy = false,
                hasSelectedFeed = false,
                feedListKeysActive = false,
                hasRenamableSelection = false,
                selectedFeedHasSiteUrl = false,
                selectedFeedSiteCanOpenInBrowser = false,
            )
            assertTrue(onHome.addItemsEnabled)

            val awayFromHome = sdk.menuState(
                onHome = false,
                hasSelectedArticle = false,
                selectedArticleHasUrl = false,
                selectedArticleCanOpenInBrowser = false,
                canSyncNow = false,
                searchActive = false,
                unreadOnly = false,
                opmlBusy = false,
                hasSelectedFeed = false,
                feedListKeysActive = false,
                hasRenamableSelection = false,
                selectedFeedHasSiteUrl = false,
                selectedFeedSiteCanOpenInBrowser = false,
            )
            assertFalse(awayFromHome.addItemsEnabled)

            // "Sync now" follows the shared ManualSync predicate it is handed, on Home only.
            fun syncEnabled(onHome: Boolean, canSyncNow: Boolean) = sdk.menuState(
                onHome = onHome,
                hasSelectedArticle = false,
                selectedArticleHasUrl = false,
                selectedArticleCanOpenInBrowser = false,
                canSyncNow = canSyncNow,
                searchActive = false,
                unreadOnly = false,
                opmlBusy = false,
                hasSelectedFeed = false,
                feedListKeysActive = false,
                hasRenamableSelection = false,
                selectedFeedHasSiteUrl = false,
                selectedFeedSiteCanOpenInBrowser = false,
            ).syncEnabled
            assertTrue(syncEnabled(onHome = true, canSyncNow = true))
            assertFalse(syncEnabled(onHome = true, canSyncNow = false))
            assertFalse(syncEnabled(onHome = false, canSyncNow = true))

            // Rename/Delete stay clickable with a selection; only their bare accelerator follows
            // whether the sidebar's own item keys are live (HomeCommands' `.keyboardShortcut`).
            fun renameState(feedListKeysActive: Boolean) = sdk.menuState(
                onHome = true,
                hasSelectedArticle = false,
                selectedArticleHasUrl = false,
                selectedArticleCanOpenInBrowser = false,
                canSyncNow = false,
                searchActive = false,
                unreadOnly = false,
                opmlBusy = false,
                hasSelectedFeed = true,
                feedListKeysActive = feedListKeysActive,
                hasRenamableSelection = true,
                selectedFeedHasSiteUrl = false,
                selectedFeedSiteCanOpenInBrowser = false,
            )
            assertTrue(renameState(feedListKeysActive = false).renameOrDeleteEnabled)
            assertFalse(renameState(feedListKeysActive = false).renameOrDeleteShortcutActive)
            assertTrue(renameState(feedListKeysActive = true).renameOrDeleteShortcutActive)

            // Import/Export follow the shared OPML busy flag the SwiftUI menu passes in.
            fun opmlEnabled(opmlBusy: Boolean) = sdk.menuState(
                onHome = true,
                hasSelectedArticle = false,
                selectedArticleHasUrl = false,
                selectedArticleCanOpenInBrowser = false,
                canSyncNow = false,
                searchActive = false,
                unreadOnly = false,
                opmlBusy = opmlBusy,
                hasSelectedFeed = false,
                feedListKeysActive = false,
                hasRenamableSelection = false,
                selectedFeedHasSiteUrl = false,
                selectedFeedSiteCanOpenInBrowser = false,
            ).opmlEnabled
            assertTrue(opmlEnabled(opmlBusy = false))
            assertFalse(opmlEnabled(opmlBusy = true))
        } finally {
            sdk.close()
        }
    }

    @Test
    fun importOpenedOpmlRequestsTheImportFromTheDataTabWithoutNotifying() = runTest {
        val sdk = start()
        try {
            sdk.importOpenedOpml("<opml><body></body></opml>")

            assertEquals(OpmlRequest.ImportDocument("<opml><body></body></opml>"), sdk.opmlController.pendingRequest.value)
            assertTrue(sdk.notificationCenter.items.value.isEmpty(), "the result is shown on the Data tab instead")

            // An unreadable file is still requested, so the Data tab shows the failure.
            sdk.importOpenedOpml(null)
            assertEquals(OpmlRequest.ImportDocument(null), sdk.opmlController.pendingRequest.value)
        } finally {
            sdk.close()
        }
    }

    @Test
    fun theRealOpmlControllerHoldsAnImportedDocumentUntilItFinishesAndRefusesASecondRun() = runTest {
        val sdk = start()
        try {
            val controller = sdk.opmlController
            // No feeds, so the import needs no network.
            val document = "<opml><body></body></opml>"

            // UNDISPATCHED: runs up to the import's first suspension (awaiting the import on the app
            // scope), so the controller is held when this returns and the run cannot finish before
            // the test body suspends.
            val first = async(start = CoroutineStart.UNDISPATCHED) { controller.importDocument(document) }
            assertTrue(controller.busy.value, "busy from the moment the import begins")
            assertFalse(controller.importDocument(document), "a second run is refused while the first holds it")

            assertTrue(first.await())
            assertFalse(controller.busy.value)
            assertEquals(OpmlResult.Imported(added = 0, failed = 0), controller.result.value)
        } finally {
            sdk.close()
        }
    }

    @Test
    fun theOpmlControllerRunsItsImportsOnTheScopeCloseStops() = runTest {
        val sdk = start()
        // Taken before close(), which closes the Koin graph it comes from.
        val controller = sdk.opmlController

        sdk.close()

        // Had the graph wired the controller to any scope other than the one close() cancels, this
        // import would start (against the closed database) instead of being refused as cancelled.
        // (A null document returns ImportFailed before reaching the scope, so this passes a real one.)
        assertFailsWith<CancellationException> { controller.importResult("<opml><body></body></opml>") }
    }

    @Test
    fun startMaintenanceIsIdempotent() = runTest {
        val sdk = start()
        try {
            // A fresh dataDirectory has no completed setup, so runStartupMaintenance's own gate
            // makes both calls near-instant no-ops; this only asserts calling it twice doesn't
            // start a second overlapping loop (which close() below would then fail to join cleanly).
            sdk.startMaintenance()
            sdk.startMaintenance()
        } finally {
            sdk.close()
        }
    }
}
