package works.merc.keryx.app.ui.settings

import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import works.merc.keryx.app.FakeCloudConnectFlow
import works.merc.keryx.app.FakeTokenStorage
import works.merc.keryx.app.SuspendingCloudConnectFlow
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_STATE_LAST_SYNCED_AT
import works.merc.keryx.app.data.cloud.CloudFile
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.opml.OpmlCodec
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.CloudConnectFlow
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.domain.TagRepository
import works.merc.keryx.app.domain.UpdateChecker
import works.merc.keryx.app.domain.UpdateStatus
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.multiProviderCloudSession
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.singleProviderCloudSession
import works.merc.keryx.app.ui.home.formatTimestamp
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A minimal valid RSS document, for the OPML import tests' `subscribeFeed` calls. */
private const val RSS = """<?xml version="1.0"?><rss version="2.0"><channel>
<title>Feed</title><link>https://ex.com</link>
<item><title>Post</title><link>https://ex.com/1</link><guid>g1</guid></item>
</channel></rss>"""

/** A [NotificationMessages] fake (unused by SettingsViewModel tests but needed to build a [FeedRepository]). */
/** A [CloudStorage] whose every operation fails with an auth error, to drive a sync failure. */
private class AlwaysFailingCloudStorage : CloudStorage {
    private fun <T> fail(): Result<T> = Result.Err(CloudAuthException("no token"))
    override suspend fun authenticate(): Result<Unit> = fail()
    override suspend fun download(path: String): Result<CloudFile> = fail()
    override suspend fun upload(path: String, data: ByteArray, expectedRev: String?): Result<Unit> = fail()
    override suspend fun create(path: String, data: ByteArray): Result<Unit> = fail()
    override suspend fun delete(path: String): Result<Unit> = fail()
    override suspend fun exists(path: String): Result<Boolean> = fail()
}

private class SettingsViewModelTestNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String): String = "gone:$feedTitle"
    override suspend fun feedUrlChanged(feedTitle: String): String = "urlChanged:$feedTitle"
    override suspend fun newArticles(count: Int): String = "new:$count"
    override suspend fun syncFailed(exception: works.merc.keryx.app.core.KeryxException): String = "syncFailed:${exception::class.simpleName}"
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: KeryxDatabase
    private val dir = FileIO.join(AppDirs.tempDir(), "settings-vm-test-${Random.nextInt()}")

    // ViewModels created via newViewModel(). Their viewModelScope is not tied to runTest's scope,
    // so it must be cancelled explicitly before driver.close() — otherwise the init collector and
    // in-flight action coroutines outlive the test and can throw against the closed driver,
    // surfacing (flakily, on another test) as kotlinx.coroutines.test.UncaughtExceptionsBeforeTest.
    private val createdViewModels = mutableListOf<SettingsViewModel>()

    /** The SyncRepository handed to the most recently built ViewModel, so a test can drive it. */
    private lateinit var createdSyncRepository: SyncRepository

    // Every SyncRepository built by newViewModel() gets its own scope for scheduleSync(); track
    // them all (not just the latest) so tearDown() can cancel every one, same as createdViewModels.
    private val createdSyncScopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val (d, database) = inMemoryDb()
        driver = d
        db = database
    }

    @AfterTest
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
        createdSyncScopes.forEach { it.cancel() }
        createdSyncScopes.clear()
        Dispatchers.resetMain()
        driver.close()
        FileIO.delete(FileIO.join(dir, "local_settings.json"))
    }

    private fun failingFetcher(): FeedFetcher {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FeedFetcher(client)
    }

    private fun missingFaviconResolver(): FaviconResolver {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FaviconResolver(client)
    }

    /** A [FeedFetcher] that answers every request with a minimal valid RSS document. */
    private fun rssFetcher(): FeedFetcher {
        val client = HttpClient(MockEngine { respond(RSS, HttpStatusCode.OK) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FeedFetcher(client)
    }

    /** A [FeedFetcher] that permanently redirects [from] to [to], then answers with a minimal RSS document. */
    private fun redirectingFetcher(from: String, to: String): FeedFetcher {
        val client = HttpClient(
            MockEngine { request ->
                if (request.url.toString() == from) {
                    respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, to))
                } else {
                    respond(RSS, HttpStatusCode.OK)
                }
            },
        ) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FeedFetcher(client)
    }

    /** An [HttpClient] that answers every request (e.g. revoke) with 200 OK — for auth managers. */
    private fun okAuthClient(): HttpClient =
        HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { expectSuccess = false }

    private fun updateCheckerReturning(tagName: String): UpdateChecker {
        // currentVersion 1.0.0 is stable, so the checker queries releases/latest (a single object).
        val client = HttpClient(
            MockEngine { respond("""{"tag_name":"$tagName","html_url":"https://ex.com/releases/$tagName","prerelease":false,"draft":false}""", HttpStatusCode.OK) },
        ) { expectSuccess = false }
        return UpdateChecker(client, currentVersion = "1.0.0", repoSlug = "owner/repo")
    }

    private fun newViewModel(
        connectResult: Result<OAuthTokens> = Result.Ok(OAuthTokens("AT")),
        tokenStorage: TokenStorage = FakeTokenStorage(),
        clock: Clock = Clock { 0L },
        updateChecker: UpdateChecker = updateCheckerReturning("1.0.0"),
        // Only the OPML import tests need subscribeFeed to actually succeed.
        feedFetcher: FeedFetcher = failingFetcher(),
        connectFlow: CloudConnectFlow? = null,
        // Lets a test supply a pre-built (e.g. multi-provider) session instead of the single-Dropbox
        // one built below, for scenarios like switchTo() that need >1 provider registered at once.
        cloudSession: CloudSession? = null,
        // Shared with the SyncRepository built below so a test can drive activityCenter.trackSync {}
        // to simulate a sync completing and assert the ViewModel reacts to it.
        activityCenter: ActivityCenter = ActivityCenter(),
        // Backs the SyncRepository built below. Default: local-only (every sync is a no-op success);
        // a test can supply a failing storage to exercise the sync-error state.
        syncCloudProvider: () -> CloudStorage? = { null },
    ): SettingsViewModel {
        val syncScheduler = SyncScheduler {}
        val articleRepository = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)
        // Mirror startup: ensureIndexed() creates articles_fts so subscribeFeed's indexMissing() works.
        val ftsManager = FtsManager(driver).also { it.ensureIndexed() }
        val feedRepository = FeedRepository(
            db, feedFetcher, missingFaviconResolver(), articleRepository, ftsManager, syncScheduler,
            NotificationCenter(), SettingsViewModelTestNotificationMessages(), clock, Dispatchers.Unconfined,
        )
        val folderRepository = FolderRepository(db, syncScheduler, clock, Dispatchers.Unconfined)
        val tagRepository = TagRepository(db, syncScheduler, clock, Dispatchers.Unconfined)
        // Unconfined write dispatcher so saveLocalSettings persists inline (localSettingsRoundTripsThroughStore
        // reads it back via store.load()).
        val settingsRepository =
            SettingsRepository(db, LocalSettingsStore(dirOverride = dir), syncScheduler, clock, writeDispatcher = Dispatchers.Unconfined)
        val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        createdSyncScopes += syncScope
        val syncRepository = SyncRepository(
            driver = driver,
            db = db,
            ftsManager = FtsManager(driver),
            cloudProvider = syncCloudProvider,
            clock = clock,
            scope = syncScope,
            activityCenter = activityCenter,
            notificationCenter = NotificationCenter(),
            notificationMessages = SettingsViewModelTestNotificationMessages(),
            localDbPath = "unused",
            tempDir = "unused",
        )
        val session = cloudSession ?: run {
            val authClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { expectSuccess = false }
            val authManager = DropboxAuthManager(authClient, clock = clock)
            singleProviderCloudSession(
                client = authClient,
                tokenStorage = tokenStorage,
                authManager = authManager,
                clock = clock,
                connectFlow = connectFlow ?: FakeCloudConnectFlow(connectResult),
            )
        }
        createdSyncRepository = syncRepository
        return SettingsViewModel(
            settingsRepository, session, syncRepository, feedRepository, folderRepository, tagRepository,
            updateChecker, activityCenter, Dispatchers.Unconfined,
        ).also { createdViewModels += it }
    }

    @Test
    fun themeModeSetterPersistsToLocalSettings() = runTest {
        val vm = newViewModel()

        vm.setThemeMode("dark")

        assertEquals("dark", vm.localSettings.value.themeMode)
    }

    @Test
    fun fontScaleSetterPersistsToLocalSettings() = runTest {
        val vm = newViewModel()

        vm.setFontScale(1.5)

        assertEquals(1.5, vm.localSettings.value.fontSizeScale)
    }

    @Test
    fun refreshIntervalSetterPersistsToLocalSettings() = runTest {
        val vm = newViewModel()

        vm.setRefreshIntervalMinutes(15)

        assertEquals(15, vm.localSettings.value.refreshIntervalMinutes)
    }

    @Test
    fun notificationEnabledSetterPersistsToLocalSettings() = runTest {
        val vm = newViewModel()

        vm.setNotificationEnabled(false)

        assertFalse(vm.localSettings.value.notificationEnabled)
    }

    @Test
    fun startMinimizedSetterPersistsToLocalSettings() = runTest {
        val vm = newViewModel()

        vm.setStartMinimized(true)

        assertTrue(vm.localSettings.value.startMinimized)
    }

    @Test
    fun localSettingsRoundTripsThroughStore() = runTest {
        val store = LocalSettingsStore(dirOverride = dir)
        val vm = newViewModel()

        vm.setThemeMode("dark")

        assertEquals("dark", store.load().themeMode)
    }

    @Test
    fun updateReadTimeoutUpdatesStateAndRepository() = runTest {
        val vm = newViewModel()

        vm.updateReadTimeout(60)

        assertEquals(60, vm.readTimeoutSeconds)
        assertEquals(60, db.global_settingsQueries.get("read_timeout_seconds").executeAsOne().toInt())
    }

    @Test
    fun updateCacheRetentionUpdatesStateAndRepositoryWithNullSentinel() = runTest {
        val vm = newViewModel()

        vm.updateCacheRetention(null)

        assertNull(vm.cacheRetentionDays)
        assertEquals("null", db.global_settingsQueries.get("cache_retention_days").executeAsOne())
    }

    @Test
    fun updateCacheRetentionUpdatesStateAndRepositoryWithExplicitValue() = runTest {
        val vm = newViewModel()

        vm.updateCacheRetention(7)

        assertEquals(7, vm.cacheRetentionDays)
        assertEquals(7, db.global_settingsQueries.get("cache_retention_days").executeAsOne().toInt())
    }

    @Test
    fun connectSuccessUpdatesConnectedTypeAndCloudStorageType() = runTest {
        val tokenStorage = FakeTokenStorage()
        val vm = newViewModel(
            connectResult = Result.Ok(OAuthTokens("AT")),
            tokenStorage = tokenStorage,
        )
        assertNull(vm.connectedType)
        assertNull(vm.connectingType)

        vm.connect(CloudStorageType.DROPBOX)
        testScheduler.advanceUntilIdle()

        assertEquals(CloudStorageType.DROPBOX, vm.connectedType)
        assertNull(vm.connectingType)
        assertFalse(vm.canCancelConnect)
        assertEquals("dropbox", vm.localSettings.value.cloudStorageType)
        assertEquals("AT", tokenStorage.load()?.accessToken)
    }

    @Test
    fun connectFailureResetsConnectingButNotConnected() = runTest {
        val vm = newViewModel(
            connectResult = Result.Err(CloudAuthException("connect failed")),
        )

        vm.connect(CloudStorageType.DROPBOX)
        testScheduler.advanceUntilIdle()

        assertNull(vm.connectedType)
        assertNull(vm.connectingType)
        assertFalse(vm.canCancelConnect)
        assertNull(vm.localSettings.value.cloudStorageType)
        assertEquals(CloudStorageType.DROPBOX, vm.connectFailedType)
    }

    @Test
    fun cancelConnectDuringOAuthWaitResetsConnectingStateAndDoesNotPersist() = runTest {
        val tokenStorage = FakeTokenStorage()
        val vm = newViewModel(tokenStorage = tokenStorage, connectFlow = SuspendingCloudConnectFlow())

        vm.connect(CloudStorageType.DROPBOX)
        testScheduler.advanceUntilIdle()
        assertEquals(CloudStorageType.DROPBOX, vm.connectingType)
        assertTrue(vm.canCancelConnect)

        vm.cancelConnect()
        testScheduler.advanceUntilIdle()

        assertNull(vm.connectingType)
        assertFalse(vm.canCancelConnect)
        assertNull(vm.connectedType)
        assertNull(vm.connectFailedType)
        assertNull(vm.localSettings.value.cloudStorageType)
        assertNull(tokenStorage.load())
    }

    @Test
    fun connectSuccessAfterPriorFailureClearsConnectFailedType() = runTest {
        // A fresh VM per attempt (newViewModel takes a fixed connect result), mirroring how a real
        // retry would resolve to Ok on the second attempt.
        val failedVm = newViewModel(
            connectResult = Result.Err(CloudAuthException("connect failed")),
        )
        failedVm.connect(CloudStorageType.DROPBOX)
        testScheduler.advanceUntilIdle()
        assertEquals(CloudStorageType.DROPBOX, failedVm.connectFailedType)

        val retriedVm = newViewModel(
            connectResult = Result.Ok(OAuthTokens("AT")),
        )
        retriedVm.connect(CloudStorageType.DROPBOX)
        testScheduler.advanceUntilIdle()

        assertEquals(CloudStorageType.DROPBOX, retriedVm.connectedType)
        assertNull(retriedVm.connectFailedType)
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler. `disconnect`
    // performs a real (mocked) HTTP revoke call whose completion is dispatched on a real
    // thread outside the TestCoroutineScheduler, so we poll with real wall-clock waits instead.
    @Test
    fun disconnectClearsConnectedTypeAndCloudStorageType() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        val vm = newViewModel(tokenStorage = tokenStorage)
        assertEquals(CloudStorageType.DROPBOX, vm.connectedType)

        vm.disconnect()
        awaitTrue { vm.connectedType == null }

        assertNull(vm.connectedType)
        assertNull(vm.localSettings.value.cloudStorageType)
        assertNull(tokenStorage.load())
    }

    // Note: these tests deliberately avoid `runTest`'s virtual scheduler, same reason as
    // disconnectClearsConnectedTypeAndCloudStorageType above — switchTo's disconnect(oldType) call
    // performs a real (mocked) HTTP revoke whose completion is dispatched on a real thread outside
    // the TestCoroutineScheduler, so we poll with real wall-clock waits instead.
    @Test
    fun switchToDisconnectsOldProviderAndConnectsNewProvider() {
        val dropboxTokenStorage = FakeTokenStorage(OAuthTokens("AT"))
        val googleDriveTokenStorage = FakeTokenStorage()
        val session = multiProviderCloudSession(
            client = okAuthClient(),
            dropboxTokenStorage = dropboxTokenStorage,
            googleDriveTokenStorage = googleDriveTokenStorage,
            googleDriveConnectFlow = FakeCloudConnectFlow(Result.Ok(OAuthTokens("AT2"))),
        )
        val vm = newViewModel(cloudSession = session)
        assertEquals(CloudStorageType.DROPBOX, vm.connectedType)

        vm.switchTo(CloudStorageType.GOOGLE_DRIVE)
        awaitTrue { vm.connectingType == null }

        assertEquals(CloudStorageType.GOOGLE_DRIVE, vm.connectedType)
        assertNull(vm.connectingType)
        assertNull(dropboxTokenStorage.load())
        assertEquals("AT2", googleDriveTokenStorage.load()?.accessToken)
        assertEquals("google_drive", vm.localSettings.value.cloudStorageType)
    }

    @Test
    fun switchToFailureLeavesLocalOnly() {
        val dropboxTokenStorage = FakeTokenStorage(OAuthTokens("AT"))
        val googleDriveTokenStorage = FakeTokenStorage()
        val session = multiProviderCloudSession(
            client = okAuthClient(),
            dropboxTokenStorage = dropboxTokenStorage,
            googleDriveTokenStorage = googleDriveTokenStorage,
            googleDriveConnectFlow = FakeCloudConnectFlow(Result.Err(CloudAuthException("connect failed"))),
        )
        val vm = newViewModel(cloudSession = session)
        assertEquals(CloudStorageType.DROPBOX, vm.connectedType)

        vm.switchTo(CloudStorageType.GOOGLE_DRIVE)
        awaitTrue { vm.connectFailedType == CloudStorageType.GOOGLE_DRIVE }

        assertNull(vm.connectedType)
        assertEquals(CloudStorageType.GOOGLE_DRIVE, vm.connectFailedType)
        assertNull(vm.connectingType)
        // The Dropbox disconnect already happened (and is irreversible) before the Google Drive
        // connect attempt was made and failed — this is intentional per switchTo's design, not a bug.
        assertNull(dropboxTokenStorage.load())
        assertNull(googleDriveTokenStorage.load())
        assertNull(vm.localSettings.value.cloudStorageType)
    }

    @Test
    fun lastSyncedAtTextReflectsSyncStateOnInit() {
        val expectedMillis = 1_234_567_890_123L
        db.sync_stateQueries.upsert(SYNC_STATE_LAST_SYNCED_AT, expectedMillis.toString())

        val vm = newViewModel()

        assertEquals(formatTimestamp(expectedMillis), vm.lastSyncedAtText)
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler, same reason as
    // disconnectClearsConnectedTypeAndCloudStorageType above — the ViewModel's reactive collector
    // runs on the standalone UnconfinedTestDispatcher installed as Main in setUp(), not on
    // runTest's own TestCoroutineScheduler, so we poll with real wall-clock waits instead.
    @Test
    fun lastSyncedAtTextRefreshesWhenActivityCenterReportsSyncCompletion() {
        val activityCenter = ActivityCenter()
        val vm = newViewModel(activityCenter = activityCenter)
        assertNull(vm.lastSyncedAtText)

        // Simulate what SyncRepository.sync() does on success: write the new sync_state row, then
        // report a sync cycle through the same ActivityCenter the ViewModel observes. A short real
        // delay (every real sync does at least one suspending network call) gives ActivityCenter's
        // internal syncing StateFlow — derived via an async map{}.stateIn(Dispatchers.Default)
        // pipeline — a chance to actually observe the true state before it flips back to false.
        val newMillis = 1_234_567_890_123L
        db.sync_stateQueries.upsert(SYNC_STATE_LAST_SYNCED_AT, newMillis.toString())
        runBlocking { activityCenter.trackSync { delay(50) } }

        awaitTrue { vm.lastSyncedAtText == formatTimestamp(newMillis) }
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler, same reason as
    // disconnectClearsConnectedTypeAndCloudStorageType above.
    @Test
    fun disconnectClearsLastSyncedAtText() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        db.sync_stateQueries.upsert(SYNC_STATE_LAST_SYNCED_AT, "1234567890123")
        val vm = newViewModel(tokenStorage = tokenStorage)
        assertEquals(CloudStorageType.DROPBOX, vm.connectedType)
        assertNotNull(vm.lastSyncedAtText)

        vm.disconnect()
        awaitTrue { vm.connectedType == null }

        assertNull(vm.lastSyncedAtText)
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler, same reason as
    // disconnectClearsConnectedTypeAndCloudStorageType above.
    @Test
    fun disconnectClearsLastSyncErrorText() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        val cloud = AlwaysFailingCloudStorage()
        val vm = newViewModel(tokenStorage = tokenStorage, syncCloudProvider = { cloud })
        runBlocking { createdSyncRepository.sync() }
        awaitTrue { vm.lastSyncErrorText == "syncFailed:CloudAuthException" }

        vm.disconnect()
        awaitTrue { vm.connectedType == null }

        assertNull(vm.lastSyncErrorText)
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler, same reason as
    // disconnectClearsConnectedTypeAndCloudStorageType above — switchTo's disconnect(oldType) call
    // performs a real (mocked) HTTP revoke whose completion is dispatched on a real thread outside
    // the TestCoroutineScheduler, so we poll with real wall-clock waits instead.
    @Test
    fun switchToClearsLastSyncErrorTextFromOldProvider() {
        val dropboxTokenStorage = FakeTokenStorage(OAuthTokens("AT"))
        val googleDriveTokenStorage = FakeTokenStorage()
        val session = multiProviderCloudSession(
            client = okAuthClient(),
            dropboxTokenStorage = dropboxTokenStorage,
            googleDriveTokenStorage = googleDriveTokenStorage,
            // Blocks indefinitely on OAuth, so the new provider's own connect/sync never runs —
            // isolating the fix (clearing on disconnect) from a later successful sync also clearing it.
            googleDriveConnectFlow = SuspendingCloudConnectFlow(),
        )
        val cloud = AlwaysFailingCloudStorage()
        val vm = newViewModel(cloudSession = session, syncCloudProvider = { cloud })
        runBlocking { createdSyncRepository.sync() }
        awaitTrue { vm.lastSyncErrorText == "syncFailed:CloudAuthException" }

        vm.switchTo(CloudStorageType.GOOGLE_DRIVE)
        // connectingType flips to GOOGLE_DRIVE synchronously at the top of switchTo(), before the old
        // provider is even disconnected — wait for canCancelConnect instead, which only becomes true
        // once connect(newType) is underway (i.e. after clearLastSyncError() has already run).
        awaitTrue { vm.canCancelConnect }

        assertNull(vm.lastSyncErrorText)
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler, same reason as
    // disconnectClearsConnectedTypeAndCloudStorageType above.
    @Test
    fun lastSyncErrorTextMirrorsSyncRepositoryLastSyncError() {
        // The cloud-sync tab shows this as the reason the connected provider isn't syncing, so it has
        // to track SyncRepository.lastSyncError in both directions.
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        val cloud = AlwaysFailingCloudStorage()
        var failing = true
        val vm = newViewModel(tokenStorage = tokenStorage, syncCloudProvider = { if (failing) cloud else null })
        assertNull(vm.lastSyncErrorText)

        runBlocking { createdSyncRepository.sync() }
        awaitTrue { vm.lastSyncErrorText == "syncFailed:CloudAuthException" }

        // Local-only from here on, so the next sync is a success and must clear the reason.
        failing = false
        runBlocking { createdSyncRepository.sync() }
        awaitTrue { vm.lastSyncErrorText == null }
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler, same reason as
    // disconnectClearsConnectedTypeAndCloudStorageType above — UpdateChecker makes a real
    // (mocked) HTTP call whose completion is dispatched on a real thread outside the
    // TestCoroutineScheduler, so we poll with real wall-clock waits instead.
    @Test
    fun checkForUpdateSurfacesAvailableResultWithoutTouchingLastUpdateCheckAt() {
        val vm = newViewModel(updateChecker = updateCheckerReturning("2.0.0"))
        assertNull(vm.updateCheckResult)

        vm.checkForUpdate()
        awaitTrue { vm.updateCheckResult != null }

        val result = vm.updateCheckResult
        assertIs<UpdateStatus.Available>(result)
        assertEquals("2.0.0", result.version)
        assertFalse(vm.checkingForUpdate)
        // Manual checks are deliberately excluded from the automatic schedule (see SettingsViewModel).
        assertNull(vm.localSettings.value.lastUpdateCheckAt)
    }

    @Test
    fun checkForUpdateIgnoresOverlappingCallsWhileOneIsInFlight() {
        var requestCount = 0
        val client = HttpClient(
            MockEngine {
                requestCount++
                delay(50)
                respond(
                    """{"tag_name":"2.0.0","html_url":"https://ex.com/releases/2.0.0","prerelease":false,"draft":false}""",
                    HttpStatusCode.OK,
                )
            },
        ) { expectSuccess = false }
        val vm = newViewModel(updateChecker = UpdateChecker(client, currentVersion = "1.0.0", repoSlug = "owner/repo"))

        vm.checkForUpdate()
        awaitTrue { vm.checkingForUpdate }
        vm.checkForUpdate() // ignored: a check is already in flight

        awaitTrue { vm.updateCheckResult != null }
        assertEquals(1, requestCount)
        assertFalse(vm.checkingForUpdate)
    }

    @Test
    fun buildOpmlDocumentGroupsFeedsByFolderInDisplayOrderAndAnnotatesTags() {
        db.insertFolder("d1", "Tech", sortOrder = 0L)
        db.insertFolder("d2", "News", sortOrder = 1L)
        db.insertFolder("d3", "Empty", sortOrder = 2L)
        db.insertFeed("f1", url = "https://a.com/feed", folderId = "d1", sortOrder = 0L)
        db.insertFeed("f2", url = "https://b.com/feed", folderId = "d1", sortOrder = 1L)
        db.insertFeed("f3", url = "https://c.com/feed", folderId = "d2", sortOrder = 0L)
        db.insertFeed("f4", url = "https://d.com/feed", sortOrder = 0L) // unfoldered
        db.insertTag("t1", "kotlin", sortOrder = 0L)
        db.insertTag("t2", "daily", sortOrder = 1L)
        db.insertFeedTag("f1", "t2")
        db.insertFeedTag("f1", "t1")
        val vm = newViewModel()

        val xml = vm.buildOpmlDocument()

        assertTrue(xml.contains("""<outline text="Tech">"""))
        // Tags follow the tags' own display (sort_order) order, not attachment order.
        assertTrue(xml.contains("""category="kotlin,daily""""))
        // An empty folder has nothing to export, so it is skipped entirely.
        assertFalse(xml.contains("Empty"))

        val reimported = OpmlCodec.import(xml)
        // Folders first in folder sort order, feeds in their sort order within each, unfoldered last.
        assertEquals(
            listOf("https://a.com/feed", "https://b.com/feed", "https://c.com/feed", "https://d.com/feed"),
            reimported.map { it.xmlUrl },
        )
        assertEquals(listOf("Tech", "Tech", "News", null), reimported.map { it.folderName })
        assertEquals(listOf("kotlin", "daily"), reimported[0].tags)
        assertTrue(reimported.drop(1).all { it.tags.isEmpty() })
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler — subscribeFeed performs
    // (mocked) HTTP calls with HttpTimeout installed, which runTest's virtual time can trip into a
    // false timeout (see docs/testing.md).
    @Test
    fun applyOpmlDocumentRecreatesFoldersAndTagsFromNestedOpml(): Unit = runBlocking {
        val vm = newViewModel(feedFetcher = rssFetcher())
        val xml = """
            <opml version="2.0"><body>
              <outline text="Tech">
                <outline type="rss" text="A" xmlUrl="https://a.com/feed" category="kotlin,news"/>
                <outline type="rss" text="B" xmlUrl="https://b.com/feed"/>
              </outline>
              <outline type="rss" text="C" xmlUrl="https://c.com/feed" category="kotlin"/>
            </body></opml>
        """.trimIndent()

        val result = vm.applyOpmlDocument(xml)

        assertEquals(3, result.added)
        assertEquals(0, result.failed)
        val folders = db.foldersQueries.watchAll().executeAsList()
        assertEquals(listOf("Tech"), folders.map { it.name })
        val a = db.feedsQueries.getByUrl("https://a.com/feed").executeAsOne()
        val b = db.feedsQueries.getByUrl("https://b.com/feed").executeAsOne()
        val c = db.feedsQueries.getByUrl("https://c.com/feed").executeAsOne()
        assertEquals(folders.single().id, a.folder_id)
        assertEquals(folders.single().id, b.folder_id)
        assertNull(c.folder_id)
        // "kotlin" is shared by two feeds but resolved to a single tag row.
        assertEquals(setOf("kotlin", "news"), db.tagsQueries.watchAll().executeAsList().map { it.name }.toSet())
        assertEquals(setOf("kotlin", "news"), tagNamesOf(a.id))
        assertEquals(emptySet(), tagNamesOf(b.id))
        assertEquals(setOf("kotlin"), tagNamesOf(c.id))
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler, same reason as
    // applyOpmlDocumentRecreatesFoldersAndTagsFromNestedOpml above.
    @Test
    fun applyOpmlDocumentOverwritesAnAlreadySubscribedFeedsFolderAndTagsToMatchTheFile(): Unit = runBlocking {
        val vm = newViewModel(feedFetcher = rssFetcher())
        val first = """
            <opml version="2.0"><body>
              <outline text="Tech">
                <outline type="rss" text="A" xmlUrl="https://a.com/feed" category="kotlin,news"/>
              </outline>
            </body></opml>
        """.trimIndent()
        vm.applyOpmlDocument(first)
        val feedId = db.feedsQueries.getByUrl("https://a.com/feed").executeAsOne().id
        assertNotNull(db.feedsQueries.getById(feedId).executeAsOne().folder_id)

        // Re-import with the feed moved out of its folder and only one of the two tags kept.
        val second = """
            <opml version="2.0"><body>
              <outline type="rss" text="A" xmlUrl="https://a.com/feed" category="news"/>
            </body></opml>
        """.trimIndent()
        val result = vm.applyOpmlDocument(second)

        assertEquals(1, result.added)
        assertNull(db.feedsQueries.getById(feedId).executeAsOne().folder_id)
        assertEquals(setOf("news"), tagNamesOf(feedId))
    }

    @Test
    fun applyOpmlDocumentAppliesFolderAndTagsEvenWhenSubscribeFollowsARedirect(): Unit = runBlocking {
        val vm = newViewModel(feedFetcher = redirectingFetcher("https://old.com/feed", "https://new.com/feed"))
        val xml = """
            <opml version="2.0"><body>
              <outline text="Tech">
                <outline type="rss" text="A" xmlUrl="https://old.com/feed" category="kotlin"/>
              </outline>
            </body></opml>
        """.trimIndent()

        val result = vm.applyOpmlDocument(xml)

        assertEquals(1, result.added)
        val feed = db.feedsQueries.getByUrl("https://new.com/feed").executeAsOne()
        assertEquals(setOf("Tech"), db.foldersQueries.watchAll().executeAsList().map { it.name }.toSet())
        assertNotNull(feed.folder_id)
        assertEquals(setOf("kotlin"), tagNamesOf(feed.id))
    }

    /** The names of the tags currently attached to [feedId]. */
    private fun tagNamesOf(feedId: String): Set<String> {
        val namesById = db.tagsQueries.watchAll().executeAsList().associate { it.id to it.name }
        return db.feed_tagsQueries.watchTagIdsForFeed(feedId).executeAsList()
            .mapNotNull { namesById[it] }
            .toSet()
    }

    /** Polls with real wall-clock waits (for coroutines that hop onto a real, non-virtual dispatcher). */
    private fun awaitTrue(timeoutMs: Long = 2_000, condition: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for condition" }
            kotlinx.coroutines.delay(5)
        }
    }
}
