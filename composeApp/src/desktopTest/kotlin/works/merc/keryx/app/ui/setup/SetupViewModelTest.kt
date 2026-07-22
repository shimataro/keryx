package works.merc.keryx.app.ui.setup

import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import works.merc.keryx.app.FakeCloudConnectFlow
import works.merc.keryx.app.FakeTokenStorage
import works.merc.keryx.app.SuspendingCloudConnectFlow
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.LocalSettings
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.CloudConnectFlow
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.singleProviderCloudSession
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Minimal [NotificationMessages] fake (SyncRepository requires one; Setup tests never assert on it). */
private object SetupViewModelTestNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String): String = "gone:$feedTitle"
    override suspend fun feedUrlChanged(feedTitle: String): String = "urlChanged:$feedTitle"
    override suspend fun newArticles(count: Int): String = "new:$count"
    override suspend fun syncFailed(exception: KeryxException): String = "syncFailed:${exception::class.simpleName}"
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: KeryxDatabase
    private val dir = FileIO.join(AppDirs.tempDir(), "setup-vm-test-${Random.nextInt()}")

    // ViewModels created via newViewModel(). Their viewModelScope is not tied to runTest's scope,
    // so it must be cancelled explicitly before driver.close() — otherwise in-flight coroutines
    // outlive the test and can throw against the closed driver, surfacing (flakily, on another
    // test) as kotlinx.coroutines.test.UncaughtExceptionsBeforeTest.
    private val createdViewModels = mutableListOf<SetupViewModel>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        val (d, database) = inMemoryDb()
        driver = d
        db = database
    }

    @AfterTest
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
        Dispatchers.resetMain()
        driver.close()
        FileIO.delete(FileIO.join(dir, "local_settings.json"))
    }

    private fun newViewModel(
        connectResult: Result<OAuthTokens> = Result.Ok(OAuthTokens("AT")),
        tokenStorage: TokenStorage = FakeTokenStorage(),
        clock: Clock = Clock { 0L },
        connectFlow: CloudConnectFlow? = null,
    ): SetupViewModel {
        val syncScheduler = SyncScheduler {}
        // Unconfined write dispatcher so SettingsRepository.flush() (called on setup completion)
        // runs its disk write inline, keeping the store.load() assertions deterministic under runTest.
        val settingsRepository =
            SettingsRepository(db, LocalSettingsStore(dirOverride = dir), syncScheduler, clock, writeDispatcher = Dispatchers.Unconfined)
        val syncRepository = SyncRepository(
            driver = driver,
            db = db,
            ftsManager = FtsManager(driver),
            cloudProvider = { null },
            clock = clock,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            activityCenter = ActivityCenter(),
            notificationCenter = NotificationCenter(),
            notificationMessages = SetupViewModelTestNotificationMessages,
            localDbPath = "unused",
            tempDir = "unused",
        )
        val authClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { expectSuccess = false }
        val authManager = DropboxAuthManager(authClient, clock = clock)
        val cloudSession = singleProviderCloudSession(
            client = authClient,
            tokenStorage = tokenStorage,
            authManager = authManager,
            clock = clock,
            connectFlow = connectFlow ?: FakeCloudConnectFlow(connectResult),
        )
        return SetupViewModel(settingsRepository, cloudSession, syncRepository)
            .also { createdViewModels += it }
    }

    @Test
    fun chooseLocalOnlySavesNullCloudStorageTypeAndInvokesOnDone() = runTest {
        val vm = newViewModel()
        var onDoneCalled = false

        vm.chooseLocalOnly { onDoneCalled = true }
        testScheduler.advanceUntilIdle()

        assertTrue(onDoneCalled)
        assertEquals(SetupPhase.IDLE, vm.phase)
    }

    @Test
    fun chooseLocalOnlyClearsPreviouslySetCloudStorageType() = runTest {
        val store = LocalSettingsStore(dirOverride = dir)
        store.save(LocalSettings(cloudStorageType = "dropbox"))
        val vm = newViewModel()

        vm.chooseLocalOnly {}
        testScheduler.advanceUntilIdle()

        assertNull(store.load().cloudStorageType)
    }

    @Test
    fun connectSuccessSavesTokensAndSettingsThenInvokesOnDone() = runTest {
        val tokenStorage = FakeTokenStorage()
        val store = LocalSettingsStore(dirOverride = dir)
        val vm = newViewModel(
            connectResult = Result.Ok(OAuthTokens("AT", "RT")),
            tokenStorage = tokenStorage,
        )
        var onDoneCalled = false

        vm.connect(CloudStorageType.DROPBOX) { onDoneCalled = true }
        testScheduler.advanceUntilIdle()

        assertEquals(SetupPhase.IDLE, vm.phase)
        assertFalse(vm.canCancelConnect)
        assertTrue(onDoneCalled)
        assertEquals("AT", tokenStorage.load()?.accessToken)
        assertEquals("dropbox", store.load().cloudStorageType)
    }

    @Test
    fun connectFailureEndsInErrorPhaseAndDoesNotInvokeOnDone() = runTest {
        val vm = newViewModel(
            connectResult = Result.Err(CloudAuthException("connect failed")),
        )
        var onDoneCalled = false

        vm.connect(CloudStorageType.DROPBOX) { onDoneCalled = true }
        testScheduler.advanceUntilIdle()

        assertEquals(SetupPhase.ERROR, vm.phase)
        assertFalse(vm.canCancelConnect)
        assertFalse(onDoneCalled)
    }

    @Test
    fun cancelConnectDuringOAuthWaitResetsPhaseAndDoesNotPersistSettings() = runTest {
        val tokenStorage = FakeTokenStorage()
        val store = LocalSettingsStore(dirOverride = dir)
        val vm = newViewModel(tokenStorage = tokenStorage, connectFlow = SuspendingCloudConnectFlow())
        var onDoneCalled = false

        vm.connect(CloudStorageType.DROPBOX) { onDoneCalled = true }
        testScheduler.advanceUntilIdle()
        assertEquals(SetupPhase.CONNECTING, vm.phase)
        assertTrue(vm.canCancelConnect)

        vm.cancelConnect()
        testScheduler.advanceUntilIdle()

        assertEquals(SetupPhase.IDLE, vm.phase)
        assertFalse(vm.canCancelConnect)
        assertFalse(onDoneCalled)
        assertNull(tokenStorage.load())
        assertNull(store.load().cloudStorageType)
    }
}
