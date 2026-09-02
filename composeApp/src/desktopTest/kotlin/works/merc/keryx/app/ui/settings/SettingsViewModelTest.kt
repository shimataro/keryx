package works.merc.keryx.app.ui.settings

import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jetbrains.compose.resources.getString
import works.merc.keryx.app.FakeCloudConnectFlow
import works.merc.keryx.app.FakeTokenStorage
import works.merc.keryx.app.SuspendingCloudConnectFlow
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_STATE_LAST_SYNCED_AT
import works.merc.keryx.app.data.cloud.CloudFileMeta
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
import works.merc.keryx.app.domain.OpmlImporter
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.domain.TagRepository
import works.merc.keryx.app.data.remote.UpdateDownloader
import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.domain.InstallLaunchResult
import works.merc.keryx.app.domain.UpdateChecker
import works.merc.keryx.app.domain.UpdateInstaller
import works.merc.keryx.app.domain.UpdatePlan
import works.merc.keryx.app.domain.UpdateRepository
import works.merc.keryx.app.domain.UpdateStatus
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.multiProviderCloudSession
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.platform.FileSelector
import works.merc.keryx.app.platform.OpenFileRequest
import works.merc.keryx.app.platform.PathPickedFile
import works.merc.keryx.app.platform.PickedFile
import works.merc.keryx.app.platform.SaveFileRequest
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.settings_export_opml
import works.merc.keryx.app.resources.settings_import_opml
import works.merc.keryx.app.singleProviderCloudSession
import works.merc.keryx.app.ui.home.formatTimestamp
import works.merc.keryx.app.ftsManagerIndexed
import kotlin.coroutines.CoroutineContext
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
/** A [CloudStorage] whose every operation fails with an auth error, to drive a sync failure. */
private class AlwaysFailingCloudStorage : CloudStorage {
    private fun <T> fail(): Result<T> = Result.Err(CloudAuthException("no token"))
    override suspend fun authenticate(): Result<Unit> = fail()
    override suspend fun download(path: String, destPath: String): Result<CloudFileMeta> = fail()
    override suspend fun upload(path: String, sourcePath: String, expectedRev: String?): Result<CloudFileMeta> = fail()
    override suspend fun create(path: String, sourcePath: String): Result<CloudFileMeta> = fail()
    override suspend fun delete(path: String): Result<Unit> = fail()
    override suspend fun rename(from: String, to: String): Result<Unit> = fail()
    override suspend fun metadata(path: String): Result<CloudFileMeta?> = fail()
}

/**
 * A [FileSelector] fake: hands back a handle to a fixed path (or null, i.e. "cancelled") and records
 * what it was asked for. The handle is the production [PathPickedFile], so reads and writes still go
 * to a real file on disk — which is what these tests assert on.
 */
private class FakeFileSelector(
    private val openPath: String? = null,
    private val savePath: String? = null,
) : FileSelector {
    var lastOpenRequest: OpenFileRequest? = null
        private set
    var lastSaveRequest: SaveFileRequest? = null
        private set

    override suspend fun pickOpenFile(request: OpenFileRequest): PickedFile? {
        lastOpenRequest = request
        return openPath?.let(::PathPickedFile)
    }

    override suspend fun pickSaveFile(request: SaveFileRequest): PickedFile? {
        lastSaveRequest = request
        return savePath?.let(::PathPickedFile)
    }
}

/** A [FileSelector] whose open pick suspends until the test resolves [openDeferred] — for exercising the in-flight state of a still-running import. */
private class SuspendingFileSelector : FileSelector {
    val openDeferred = CompletableDeferred<PickedFile?>()
    override suspend fun pickOpenFile(request: OpenFileRequest): PickedFile? = openDeferred.await()
    override suspend fun pickSaveFile(request: SaveFileRequest): PickedFile? = error("not used by this test")
}

/**
 * Counts [dispatch] calls, so a test can assert that `withContext(dispatcher)` actually ran — then
 * runs the block immediately rather than delegating to [Dispatchers.Unconfined], whose real
 * synchronous-execution trick lives behind `isDispatchNeeded() == false` and isn't reached when
 * `dispatch()` is invoked directly (calling it here left the resumed coroutine parked instead of
 * run, so `awaitTrue` timed out).
 */
private class CountingDispatcher : CoroutineDispatcher() {
    var dispatchCount = 0
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchCount++
        block.run()
    }
}

/** Throws [CancellationException] the moment work is dispatched to it — simulates the coroutine being cancelled mid-`withContext`. */
private class CancellingDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        throw CancellationException("cancelled for test")
    }
}

private class SettingsViewModelTestNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String): String = "gone:$feedTitle"
    override suspend fun feedUrlChanged(feedTitle: String): String = "urlChanged:$feedTitle"
    override suspend fun newArticles(count: Int): String = "new:$count"
    override suspend fun syncFailed(exception: works.merc.keryx.app.core.KeryxException): String = "syncFailed:${exception::class.simpleName}"
    override suspend fun opmlImported(added: Int, failed: Int): String = "opmlImported:$added/$failed"
    override suspend fun updateAvailable(version: String): String = "updateAvailable:$version"
    override suspend fun updateReadyToInstall(version: String): String = "updateReadyToInstall:$version"
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
        // cancelAndJoin (not plain cancel) so no coroutine — including work hopping through
        // withContext(dispatcher) for exportOpml/importOpml/etc. — can still be resuming when
        // driver.close()/resetMain() run below; a still-resuming one throwing against torn-down
        // state is what previously surfaced (flakily, on a later test) as
        // kotlinx.coroutines.test.UncaughtExceptionsBeforeTest.
        runBlocking {
            createdViewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
            createdSyncScopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        }
        createdViewModels.clear()
        createdSyncScopes.clear()
        Dispatchers.resetMain()
        driver.close()
        File(dir).deleteRecursively()
    }

    private fun failingFetcher(): FeedFetcher {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FeedFetcher(client)
    }

    /** A [FeedFetcher] that answers every request with a minimal valid feed, for OPML import tests. */
    private fun succeedingFetcher(): FeedFetcher {
        val rss = """<?xml version="1.0"?><rss version="2.0"><channel>
            <title>Feed</title><link>https://ex.com</link>
            <item><title>Post</title><link>https://ex.com/1</link><guid>g1</guid></item>
            </channel></rss>"""
        val client = HttpClient(MockEngine { respond(rss, HttpStatusCode.OK) }) {
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

    /**
     * Wraps [checker] in a real [UpdateRepository] so [SettingsViewModel.checkForUpdate] exercises
     * the same code path production does — only [downloader]/[installer] are inert stand-ins,
     * since these tests only exercise the check/result path, never an actual download or install.
     */
    private fun fakeUpdateRepository(checker: UpdateChecker): UpdateRepository {
        val unusedClient = HttpClient(MockEngine { respondError(HttpStatusCode.NotImplemented) }) { expectSuccess = false }
        val installer = object : UpdateInstaller {
            override fun canInstall(plan: UpdatePlan) = false
            override suspend fun install(filePath: String, update: AvailableUpdate) =
                InstallLaunchResult.Failed("not used in this test")
        }
        return UpdateRepository(
            checker = checker,
            downloader = UpdateDownloader(unusedClient),
            installer = installer,
            notificationCenter = NotificationCenter(),
            notificationMessages = SettingsViewModelTestNotificationMessages(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { createdSyncScopes += it },
        )
    }

    /**
     * An [ActivityCenter] whose scope is tracked in [createdSyncScopes], so tearDown() cancels
     * its eager stateIn collectors instead of leaking them for the life of the JVM test process.
     */
    private fun trackedActivityCenter(): ActivityCenter =
        ActivityCenter(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { createdSyncScopes += it },
        )

    private fun newViewModel(
        connectResult: Result<OAuthTokens> = Result.Ok(OAuthTokens("AT")),
        tokenStorage: TokenStorage = FakeTokenStorage(),
        clock: Clock = Clock { 0L },
        updateRepository: UpdateRepository = fakeUpdateRepository(updateCheckerReturning("1.0.0")),
        // Only the OPML import tests need subscribeFeed to actually succeed.
        feedFetcher: FeedFetcher = failingFetcher(),
        connectFlow: CloudConnectFlow? = null,
        // Lets a test supply a pre-built (e.g. multi-provider) session instead of the single-Dropbox
        // one built below, for scenarios like switchTo() that need >1 provider registered at once.
        cloudSession: CloudSession? = null,
        // Shared with the SyncRepository built below so a test can drive activityCenter.trackSync {}
        // to simulate a sync completing and assert the ViewModel reacts to it.
        activityCenter: ActivityCenter = trackedActivityCenter(),
        // Backs the SyncRepository built below. Default: local-only (every sync is a no-op success);
        // a test can supply a failing storage to exercise the sync-error state.
        syncCloudProvider: () -> CloudStorage? = { null },
        // Default cancels every OPML pick — a test exercising import/export supplies its own.
        fileSelector: FileSelector = FakeFileSelector(),
        // Passed to the VM's own `dispatcher` (blocking OPML build/write/import work). Default
        // matches the rest of newViewModel's Unconfined setup; a test can supply a CountingDispatcher
        // to assert it was actually used.
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): SettingsViewModel {
        val syncScheduler = SyncScheduler {}
        val articleRepository = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)
        // Mirror startup: ensureIndexed() creates articles_fts so subscribeFeed's indexMissing() works.
        val ftsManager = ftsManagerIndexed(driver)
        val feedRepository = FeedRepository(
            db, feedFetcher, missingFaviconResolver(), articleRepository, ftsManager, syncScheduler,
            NotificationCenter(), SettingsViewModelTestNotificationMessages(), clock, Dispatchers.Unconfined,
        )
        val folderRepository = FolderRepository(db, feedRepository, syncScheduler, clock, Dispatchers.Unconfined)
        val tagRepository = TagRepository(db, syncScheduler, clock, Dispatchers.Unconfined)
        val opmlImporter = OpmlImporter(feedRepository, folderRepository, tagRepository)
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
            opmlImporter, updateRepository, activityCenter, dispatcher, fileSelector,
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
        val activityCenter = trackedActivityCenter()
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
        // Await the actual condition being asserted, not just connectedType: lastSyncErrorText is
        // updated by an independent collector coroutine (init block) reacting to
        // clearSyncFailureState()'s StateFlow write, so polling connectedType alone gives no
        // happens-before guarantee for it.
        awaitTrue { vm.connectedType == null && vm.lastSyncErrorText == null }

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
        // once connect(newType) is underway (i.e. after clearSyncFailureState() has already run). Also
        // await lastSyncErrorText directly: it's updated by an independent collector coroutine
        // reacting to clearSyncFailureState()'s StateFlow write, so canCancelConnect alone gives no
        // happens-before guarantee for it (see disconnectClearsLastSyncErrorText for the same race).
        awaitTrue { vm.canCancelConnect && vm.lastSyncErrorText == null }

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
        val vm = newViewModel(updateRepository = fakeUpdateRepository(updateCheckerReturning("2.0.0")))
        assertNull(vm.updateCheckResult)

        vm.checkForUpdate()
        // Await checkingForUpdate becoming false too, not just updateCheckResult: both are written
        // sequentially in the same coroutine after the suspend point, on whatever thread the mocked
        // HTTP call resumes on — observing the first write gives no happens-before guarantee for the
        // second (see disconnectClearsLastSyncErrorText for the same class of race).
        awaitTrue { vm.updateCheckResult != null && !vm.checkingForUpdate }

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
        val vm = newViewModel(updateRepository = fakeUpdateRepository(UpdateChecker(client, currentVersion = "1.0.0", repoSlug = "owner/repo")))

        vm.checkForUpdate()
        awaitTrue { vm.checkingForUpdate }
        vm.checkForUpdate() // ignored: a check is already in flight

        // Same race guard as checkForUpdateSurfacesAvailableResultWithoutTouchingLastUpdateCheckAt.
        awaitTrue { vm.updateCheckResult != null && !vm.checkingForUpdate }
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

    @Test
    fun exportOpmlWritesTheBuiltDocumentToThePickedPath() = runTest {
        db.insertFeed("f1", url = "https://a.com/feed")
        val path = FileIO.join(dir, "export.opml")
        val vm = newViewModel(fileSelector = FakeFileSelector(savePath = path))

        vm.exportOpml()

        // The write runs on the injected (non-test) dispatcher, so it's a real, non-virtual hop.
        awaitTrue { vm.opmlResult != null }
        assertEquals(OpmlResult.Exported, vm.opmlResult)
        val written = FileIO.readText(path)
        assertNotNull(written)
        assertEquals(listOf("https://a.com/feed"), OpmlCodec.import(written).map { it.xmlUrl })
    }

    @Test
    fun exportOpmlReportsCancelledWhenTheDialogIsDismissed() = runTest {
        val vm = newViewModel(fileSelector = FakeFileSelector(savePath = null))

        vm.exportOpml()

        assertEquals(OpmlResult.Cancelled, vm.opmlResult)
    }

    @Test
    fun exportOpmlPassesTheLocalizedTitleAndOverwriteLabelsToTheDialog() = runTest {
        val selector = FakeFileSelector(savePath = null)
        val vm = newViewModel(fileSelector = selector)

        vm.exportOpml()

        val request = selector.lastSaveRequest
        assertNotNull(request)
        assertEquals(getString(Res.string.settings_export_opml), request.title)
        assertEquals("keryx.opml", request.defaultName)
        assertTrue(request.overwriteTitle.isNotBlank())
        assertTrue(request.overwriteMessage.isNotBlank())
        assertTrue(request.overwriteReplaceLabel.isNotBlank())
        assertTrue(request.overwriteCancelLabel.isNotBlank())
    }

    @Test
    fun exportOpmlRunsTheDocumentBuildAndWriteOnTheInjectedDispatcher() = runTest {
        db.insertFeed("f1", url = "https://a.com/feed")
        val path = FileIO.join(dir, "export-dispatcher.opml")
        val counting = CountingDispatcher()
        val vm = newViewModel(fileSelector = FakeFileSelector(savePath = path), dispatcher = counting)

        vm.exportOpml()

        awaitTrue { vm.opmlResult != null }
        assertEquals(OpmlResult.Exported, vm.opmlResult)
        assertTrue(counting.dispatchCount > 0)
    }

    @Test
    fun exportOpmlReportsFailedWhenTheWriteThrows() = runTest {
        val blockingFile = FileIO.join(dir, "not-a-directory")
        FileIO.writeText(blockingFile, "x")
        val path = FileIO.join(blockingFile, "export.opml")
        val vm = newViewModel(fileSelector = FakeFileSelector(savePath = path))

        vm.exportOpml()

        awaitTrue { vm.opmlResult != null }
        assertEquals(OpmlResult.ExportFailed, vm.opmlResult)
    }

    @Test
    fun exportOpmlRethrowsCancellationInsteadOfReportingFailure() = runTest {
        val path = FileIO.join(dir, "export-cancel.opml")
        val vm = newViewModel(fileSelector = FakeFileSelector(savePath = path), dispatcher = CancellingDispatcher())

        vm.exportOpml()

        assertNull(vm.opmlResult)
        assertFalse(vm.exportingOpml)
    }

    @Test
    fun importOpmlSubscribesEveryFeedInThePickedFile() = runTest {
        val xml = """<opml><body><outline text="Feed" xmlUrl="https://ex.com/feed"/></body></opml>"""
        val path = FileIO.join(dir, "import.opml")
        FileIO.writeText(path, xml)
        val vm = newViewModel(feedFetcher = succeedingFetcher(), fileSelector = FakeFileSelector(openPath = path))

        vm.importOpml()

        // The read + import (network fetch, DB writes) run on the injected (non-test) dispatcher.
        awaitTrue { vm.opmlResult != null }
        val result = vm.opmlResult
        assertIs<OpmlResult.Imported>(result)
        assertEquals(1, result.added)
        assertEquals(0, result.failed)
    }

    @Test
    fun importOpmlReportsCancelledWhenTheDialogIsDismissed() = runTest {
        val vm = newViewModel(fileSelector = FakeFileSelector(openPath = null))

        vm.importOpml()

        assertEquals(OpmlResult.Cancelled, vm.opmlResult)
    }

    @Test
    fun importOpmlReportsFailedWhenTheFileCannotBeRead() = runTest {
        val missingPath = FileIO.join(dir, "does-not-exist.opml")
        val vm = newViewModel(fileSelector = FakeFileSelector(openPath = missingPath))

        vm.importOpml()

        assertEquals(OpmlResult.ImportFailed, vm.opmlResult)
    }

    @Test
    fun importOpmlRethrowsCancellationInsteadOfReportingFailure() = runTest {
        val path = FileIO.join(dir, "import-cancel.opml")
        FileIO.writeText(path, "<opml><body/></opml>")
        val vm = newViewModel(fileSelector = FakeFileSelector(openPath = path), dispatcher = CancellingDispatcher())

        vm.importOpml()

        assertNull(vm.opmlResult)
        assertFalse(vm.importingOpml)
    }

    @Test
    fun importOpmlRunsTheReadAndImportOnTheInjectedDispatcher() = runTest {
        val xml = """<opml><body><outline text="Feed" xmlUrl="https://ex.com/feed"/></body></opml>"""
        val path = FileIO.join(dir, "import-dispatcher.opml")
        FileIO.writeText(path, xml)
        val counting = CountingDispatcher()
        val vm = newViewModel(
            feedFetcher = succeedingFetcher(),
            fileSelector = FakeFileSelector(openPath = path),
            dispatcher = counting,
        )

        vm.importOpml()

        awaitTrue { vm.opmlResult != null }
        assertIs<OpmlResult.Imported>(vm.opmlResult)
        assertTrue(counting.dispatchCount > 0)
    }

    @Test
    fun importAndExportOpmlRefuseToRunConcurrently() = runTest {
        val selector = SuspendingFileSelector()
        val vm = newViewModel(fileSelector = selector)

        vm.importOpml()
        assertTrue(vm.importingOpml)

        // Guarded no-op: importingOpml is still true, so this must not touch exportingOpml at all.
        vm.exportOpml()
        assertFalse(vm.exportingOpml)

        selector.openDeferred.complete(null)
        assertEquals(OpmlResult.Cancelled, vm.opmlResult)
        assertFalse(vm.importingOpml)
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
