package works.merc.keryx.app.ui.settings

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.CloudConnectFlow
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.domain.UpdateChecker
import works.merc.keryx.app.domain.UpdateStatus
import works.merc.keryx.app.inMemoryDb
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

/** A [NotificationMessages] fake (unused by SettingsViewModel tests but needed to build a [FeedRepository]). */
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

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val (d, database) = inMemoryDb()
        driver = d
        db = database
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        FileIO.delete(FileIO.join(dir, "local_settings.json"))
        Dispatchers.resetMain()
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
        connectFlow: CloudConnectFlow? = null,
        // Lets a test supply a pre-built (e.g. multi-provider) session instead of the single-Dropbox
        // one built below, for scenarios like switchTo() that need >1 provider registered at once.
        cloudSession: CloudSession? = null,
        // Shared with the SyncRepository built below so a test can drive activityCenter.trackSync {}
        // to simulate a sync completing and assert the ViewModel reacts to it.
        activityCenter: ActivityCenter = ActivityCenter(),
    ): SettingsViewModel {
        val syncScheduler = SyncScheduler {}
        val articleRepository = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)
        val feedRepository = FeedRepository(
            db, failingFetcher(), missingFaviconResolver(), articleRepository, FtsManager(driver), syncScheduler,
            NotificationCenter(), SettingsViewModelTestNotificationMessages(), clock, Dispatchers.Unconfined,
        )
        // Unconfined write dispatcher so saveLocalSettings persists inline (localSettingsRoundTripsThroughStore
        // reads it back via store.load()).
        val settingsRepository =
            SettingsRepository(db, LocalSettingsStore(dirOverride = dir), syncScheduler, clock, writeDispatcher = Dispatchers.Unconfined)
        val syncRepository = SyncRepository(
            driver = driver,
            db = db,
            ftsManager = FtsManager(driver),
            cloudProvider = { null },
            clock = clock,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
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
        return SettingsViewModel(
            settingsRepository, session, syncRepository, feedRepository, updateChecker, activityCenter,
            Dispatchers.Unconfined,
        )
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
        awaitTrue { vm.connectedType == CloudStorageType.GOOGLE_DRIVE }

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

    /** Polls with real wall-clock waits (for coroutines that hop onto a real, non-virtual dispatcher). */
    private fun awaitTrue(timeoutMs: Long = 2_000, condition: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for condition" }
            kotlinx.coroutines.delay(5)
        }
    }
}
