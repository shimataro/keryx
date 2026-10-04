package works.merc.keryx.app.presentation.settings

import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import works.merc.keryx.app.FakeCloudConnectFlow
import works.merc.keryx.app.FakeTokenStorage
import works.merc.keryx.app.SuspendingCloudConnectFlow
import works.merc.keryx.app.awaitConditionBlocking
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.ErrorKind
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_STATE_LAST_SYNCED_AT
import works.merc.keryx.app.data.cloud.CloudFileMeta
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.LocalSettings
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.CloudConnectFlow
import works.merc.keryx.app.domain.CloudConnectionService
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncPhase
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.multiProviderCloudSession
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.presentation.ManualSyncEdge
import works.merc.keryx.app.presentation.formatTimestamp
import works.merc.keryx.app.singleProviderCloudSession
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
 * A [CloudStorage] whose [metadata] suspends until [gate] resolves — for exercising the in-flight
 * state of a still-running sync. Every other method fails cleanly (never called in the "first sync
 * ever" path this drives: two gated [metadata] calls — compressed then legacy, both absent — land
 * on [create]).
 */
private class GatedCloudStorage(
    private val gate: CompletableDeferred<Unit>,
    private val metadataCalls: AtomicInteger? = null,
) : CloudStorage {
    private fun <T> fail(): Result<T> = Result.Err(CloudAuthException("not used by this test"))
    override suspend fun authenticate(): Result<Unit> = Result.Ok(Unit)
    override suspend fun metadata(path: String): Result<CloudFileMeta?> {
        metadataCalls?.incrementAndGet()
        gate.await()
        return Result.Ok(null)
    }
    override suspend fun download(path: String, destPath: String): Result<CloudFileMeta> = fail()
    override suspend fun upload(path: String, sourcePath: String, expectedRev: String?): Result<CloudFileMeta> = fail()
    override suspend fun create(path: String, sourcePath: String): Result<CloudFileMeta> = fail()
    override suspend fun delete(path: String): Result<Unit> = Result.Ok(Unit)
    override suspend fun rename(from: String, to: String): Result<Unit> = Result.Ok(Unit)
}

/**
 * Holds every dispatched block until [release] is called, then runs the held blocks and every later
 * one inline. Holding the body of `withContext(dispatcher)` keeps a sync from reaching
 * [works.merc.keryx.app.domain.ActivityCenter.trackSync], so the controller's `idle` stays true.
 */
private class HoldingDispatcher : CoroutineDispatcher() {
    private val held = ArrayDeque<Runnable>()
    private var released = false

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(this) {
            if (!released) {
                held.addLast(block)
                return
            }
        }
        block.run()
    }

    fun release() {
        val pending = synchronized(this) {
            released = true
            held.toList().also { held.clear() }
        }
        pending.forEach { it.run() }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CloudSyncControllerTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: KeryxDatabase
    private val dir = FileIO.join(AppDirs.tempDir(), "cloud-sync-controller-test-${Random.nextInt()}")

    // Controllers created via newController(). Their viewModelScope is not tied to runTest's scope,
    // so it must be cancelled explicitly before driver.close() — otherwise in-flight coroutines
    // outlive the test and can throw against the closed driver, surfacing (flakily, on another
    // test) as kotlinx.coroutines.test.UncaughtExceptionsBeforeTest.
    private val createdControllers = mutableListOf<CloudSyncController>()

    /** The SyncRepository handed to the most recently built controller, so a test can drive it. */
    private lateinit var createdSyncRepository: SyncRepository

    /** The SettingsRepository handed to the most recently built controller. */
    private lateinit var createdSettingsRepository: SettingsRepository

    // Every SyncRepository built by newController() gets its own scope for scheduleSync(); track
    // them all (not just the latest) so tearDown() can cancel every one, same as createdControllers.
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
        // cancelAndJoin (not plain cancel) so no coroutine can still be resuming when
        // driver.close()/resetMain() run below; a still-resuming one throwing against torn-down
        // state is what previously surfaced (flakily, on a later test) as
        // kotlinx.coroutines.test.UncaughtExceptionsBeforeTest.
        runBlocking {
            createdControllers.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
            createdSyncScopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        }
        createdControllers.clear()
        createdSyncScopes.clear()
        Dispatchers.resetMain()
        driver.close()
        File(dir).deleteRecursively()
    }

    /**
     * A [CloudSession] `selectedType` that reads the provider from the settings the next
     * [newController] builds, the way production's does (`CloudPlatformModule`), seeded with
     * [initial]. Needed wherever a test switches providers: the controller re-derives
     * `connectedType` from the session whenever `cloudStorageType` changes, so a selection pinned to
     * one provider would report the new one as disconnected.
     */
    private fun settingsBackedSelection(initial: CloudStorageType): () -> CloudStorageType? {
        LocalSettingsStore(dirOverride = dir).save(LocalSettings(cloudStorageType = initial.id))
        return { CloudStorageType.fromId(createdSettingsRepository.getLocalSettings().cloudStorageType) }
    }

    private fun okAuthClient(): HttpClient =
        HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { expectSuccess = false }

    private fun newController(
        connectResult: Result<OAuthTokens> = Result.Ok(OAuthTokens("AT")),
        tokenStorage: TokenStorage = FakeTokenStorage(),
        clock: Clock = Clock { 0L },
        connectFlow: CloudConnectFlow? = null,
        // Lets a test supply a pre-built (e.g. multi-provider) session instead of the single-Dropbox
        // one built below, for scenarios like switchTo() that need >1 provider registered at once.
        cloudSession: CloudSession? = null,
        // Shared with the SyncRepository built below so a test can drive activityCenter.trackSync {}
        // to simulate a sync completing and assert the controller reacts to it.
        activityCenter: ActivityCenter = ActivityCenter(),
        // Backs the SyncRepository built below. Default: local-only (every sync is a no-op success);
        // a test can supply a failing storage to exercise the sync-error state.
        syncCloudProvider: () -> CloudStorage? = { null },
        // Passed to the controller's own `dispatcher` (blocking token save / sync work). Default
        // matches the rest of newController's Unconfined setup; a test can supply a CoroutineDispatcher
        // to assert it was actually used or to hold work in flight.
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): CloudSyncController {
        val syncScheduler = SyncScheduler {}
        // Unconfined write dispatcher so saveLocalSettings persists inline.
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
        createdSettingsRepository = settingsRepository
        return CloudSyncController(
            session, syncRepository, CloudConnectionService(session, settingsRepository, syncRepository),
            activityCenter, settingsRepository, dispatcher,
        ).also { createdControllers += it }
    }

    @Test
    fun connectSuccessUpdatesConnectedTypeAndCloudStorageType() = runTest {
        val tokenStorage = FakeTokenStorage()
        val controller = newController(
            connectResult = Result.Ok(OAuthTokens("AT")),
            tokenStorage = tokenStorage,
        )
        assertNull(controller.connectedType.value)
        assertNull(controller.connectingType.value)

        controller.connect(CloudStorageType.DROPBOX)
        testScheduler.advanceUntilIdle()

        assertEquals(CloudStorageType.DROPBOX, controller.connectedType.value)
        assertNull(controller.connectingType.value)
        assertFalse(controller.canCancelConnect.value)
        assertEquals("AT", tokenStorage.load()?.accessToken)
    }

    @Test
    fun connectFailureResetsConnectingButNotConnected() = runTest {
        val controller = newController(
            connectResult = Result.Err(CloudAuthException("connect failed")),
        )

        controller.connect(CloudStorageType.DROPBOX)
        testScheduler.advanceUntilIdle()

        assertNull(controller.connectedType.value)
        assertNull(controller.connectingType.value)
        assertFalse(controller.canCancelConnect.value)
        assertEquals(CloudStorageType.DROPBOX, controller.connectFailedType.value)
    }

    @Test
    fun cancelConnectDuringOAuthWaitResetsConnectingStateAndDoesNotPersist() = runTest {
        val tokenStorage = FakeTokenStorage()
        val controller = newController(tokenStorage = tokenStorage, connectFlow = SuspendingCloudConnectFlow())

        controller.connect(CloudStorageType.DROPBOX)
        testScheduler.advanceUntilIdle()
        assertEquals(CloudStorageType.DROPBOX, controller.connectingType.value)
        assertTrue(controller.canCancelConnect.value)

        controller.cancelConnect()
        testScheduler.advanceUntilIdle()

        assertNull(controller.connectingType.value)
        assertFalse(controller.canCancelConnect.value)
        assertNull(controller.connectedType.value)
        assertNull(controller.connectFailedType.value)
        assertNull(tokenStorage.load())
    }

    /**
     * The split this whole feature exists to fix: once OAuth and token save finish, connectingType
     * must drop to null immediately — before the (potentially long) initial sync starts — while
     * initialSyncingType picks up covering that sync. Without this split, the cloud-sync tab's
     * "reset"/"switch provider" buttons stay correctly blocked, but so did "disconnect" (the bug),
     * which must not be gated on the same flag.
     */
    @Test
    fun initialSyncKeepsConnectingTypeClearWhileSettingInitialSyncingType() = runTest {
        val gate = CompletableDeferred<Unit>()
        val controller = newController(syncCloudProvider = { GatedCloudStorage(gate) })
        assertNull(controller.initialSyncingType.value)

        controller.connect(CloudStorageType.DROPBOX)
        testScheduler.advanceUntilIdle()

        assertEquals(CloudStorageType.DROPBOX, controller.connectedType.value)
        assertNull(controller.connectingType.value)
        assertEquals(CloudStorageType.DROPBOX, controller.initialSyncingType.value)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertNull(controller.initialSyncingType.value)
    }

    @Test
    fun syncPhaseMirrorsSyncRepositoryDuringTheInitialSync() = runTest {
        val gate = CompletableDeferred<Unit>()
        val controller = newController(syncCloudProvider = { GatedCloudStorage(gate) })
        assertEquals(SyncPhase.IDLE, controller.syncPhase.value)

        controller.connect(CloudStorageType.DROPBOX)
        testScheduler.advanceUntilIdle()

        assertEquals(SyncPhase.CHECKING, controller.syncPhase.value)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(SyncPhase.IDLE, controller.syncPhase.value)
    }

    /**
     * A manual "sync now" from Home (or anywhere else) also travels through [SyncRepository.syncPhase],
     * so the controller's collector must mirror it even when this controller didn't start the sync.
     */
    @Test
    fun syncPhaseMirrorsSyncRepositoryDuringManualSync() = runTest {
        val gate = CompletableDeferred<Unit>()
        val controller = newController(syncCloudProvider = { GatedCloudStorage(gate) })
        assertEquals(SyncPhase.IDLE, controller.syncPhase.value)

        // Drive the sync through the repository directly, not through controller.connect().
        val syncJob = launch { createdSyncRepository.sync() }
        advanceUntilIdle()

        assertEquals(SyncPhase.CHECKING, controller.syncPhase.value)

        gate.complete(Unit)
        syncJob.join()

        assertEquals(SyncPhase.IDLE, controller.syncPhase.value)
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler, same reason as
    // lastSyncedAtTextRefreshesWhenActivityCenterReportsSyncCompletion below — the controller's
    // collector runs on the standalone UnconfinedTestDispatcher installed as Main, and the sync on a
    // real Dispatchers.Default thread, neither on runTest's scheduler, so both the true and the false
    // side of the transition are polled with real wall-clock waits.
    @Test
    fun syncingMirrorsActivityCenter() {
        val activityCenter = ActivityCenter()
        val controller = newController(activityCenter = activityCenter)
        assertFalse(controller.syncing.value)

        val job = CoroutineScope(Dispatchers.Default).launch {
            activityCenter.trackSync { delay(200) }
        }

        awaitConditionBlocking { controller.syncing.value }
        awaitConditionBlocking { !controller.syncing.value }
        runBlocking { job.join() }
    }

    // Note: same reason as syncingMirrorsActivityCenter above — the sync runs on a real thread
    // outside any virtual scheduler, so this polls with real wall-clock waits. Regression test for
    // a `drop(1)`-based bug: the syncing collector used to skip the subscription-time replay of
    // ActivityCenter's sync state on the assumption it always
    // matched the value the property initializer had already captured. That assumption can fail
    // when a sync is already running before the controller is even constructed (e.g. the background
    // loop already syncing when Settings is opened) — the transition back to false can then race
    // past the subscription point and get silently dropped, leaving `syncing` stuck true forever.
    @Test
    fun syncingReflectsActivityCenterAcrossAFullCycleEvenWhenAlreadyRunningAtConstruction() {
        val activityCenter = ActivityCenter()
        val gate = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            activityCenter.trackSync { gate.await() }
        }
        awaitConditionBlocking { activityCenter.activity.value.syncing }

        val controller = newController(activityCenter = activityCenter)
        assertTrue(controller.syncing.value)

        gate.complete(Unit)
        awaitConditionBlocking { !controller.syncing.value }
        runBlocking { job.join() }
    }

    // Note: same reason as disconnectClearsConnectedTypeAndCloudStorageType below — disconnect
    // performs a real (mocked) HTTP revoke call whose completion is dispatched on a real thread
    // outside the TestCoroutineScheduler, so we poll with real wall-clock waits instead.
    @Test
    fun disconnectSetsDisconnectingUntilTeardownCompletes() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        // Holds the revoke open so the teardown cannot finish on Ktor's IO thread before the
        // in-flight assertion below runs.
        val revokeGate = CompletableDeferred<Unit>()
        val authClient = HttpClient(MockEngine { revokeGate.await(); respond("{}", HttpStatusCode.OK) }) {
            expectSuccess = false
        }
        try {
            val session = singleProviderCloudSession(
                client = authClient,
                tokenStorage = tokenStorage,
                authManager = DropboxAuthManager(authClient, clock = Clock { 0L }),
            )
            val controller = newController(tokenStorage = tokenStorage, cloudSession = session)
            assertEquals(CloudStorageType.DROPBOX, controller.connectedType.value)
            assertFalse(controller.disconnecting.value)

            controller.disconnect()
            assertTrue(controller.disconnecting.value)

            revokeGate.complete(Unit)
            awaitConditionBlocking { controller.connectedType.value == null && !controller.disconnecting.value }
            assertFalse(controller.disconnecting.value)
        } finally {
            revokeGate.complete(Unit)
            authClient.close()
        }
    }

    @Test
    fun connectSuccessAfterPriorFailureClearsConnectFailedType() = runTest {
        // A fresh controller per attempt (newController takes a fixed connect result), mirroring how
        // a real retry would resolve to Ok on the second attempt.
        val failedController = newController(
            connectResult = Result.Err(CloudAuthException("connect failed")),
        )
        failedController.connect(CloudStorageType.DROPBOX)
        testScheduler.advanceUntilIdle()
        assertEquals(CloudStorageType.DROPBOX, failedController.connectFailedType.value)

        val retriedController = newController(
            connectResult = Result.Ok(OAuthTokens("AT")),
        )
        retriedController.connect(CloudStorageType.DROPBOX)
        testScheduler.advanceUntilIdle()

        assertEquals(CloudStorageType.DROPBOX, retriedController.connectedType.value)
        assertNull(retriedController.connectFailedType.value)
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler. `disconnect`
    // performs a real (mocked) HTTP revoke call whose completion is dispatched on a real
    // thread outside the TestCoroutineScheduler, so we poll with real wall-clock waits instead.
    @Test
    fun disconnectClearsConnectedTypeAndCloudStorageType() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        val controller = newController(tokenStorage = tokenStorage)
        assertEquals(CloudStorageType.DROPBOX, controller.connectedType.value)

        controller.disconnect()
        awaitConditionBlocking { controller.connectedType.value == null }

        assertNull(controller.connectedType.value)
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
            selectedType = settingsBackedSelection(CloudStorageType.DROPBOX),
            googleDriveConnectFlow = FakeCloudConnectFlow(Result.Ok(OAuthTokens("AT2"))),
        )
        val controller = newController(cloudSession = session)
        assertEquals(CloudStorageType.DROPBOX, controller.connectedType.value)

        controller.switchTo(CloudStorageType.GOOGLE_DRIVE)
        awaitConditionBlocking { controller.connectingType.value == null }

        assertEquals(CloudStorageType.GOOGLE_DRIVE, controller.connectedType.value)
        assertNull(controller.connectingType.value)
        assertNull(dropboxTokenStorage.load())
        assertEquals("AT2", googleDriveTokenStorage.load()?.accessToken)
    }

    @Test
    fun switchToFailureLeavesLocalOnly() {
        val dropboxTokenStorage = FakeTokenStorage(OAuthTokens("AT"))
        val googleDriveTokenStorage = FakeTokenStorage()
        val session = multiProviderCloudSession(
            client = okAuthClient(),
            dropboxTokenStorage = dropboxTokenStorage,
            googleDriveTokenStorage = googleDriveTokenStorage,
            selectedType = settingsBackedSelection(CloudStorageType.DROPBOX),
            googleDriveConnectFlow = FakeCloudConnectFlow(Result.Err(CloudAuthException("connect failed"))),
        )
        val controller = newController(cloudSession = session)
        assertEquals(CloudStorageType.DROPBOX, controller.connectedType.value)

        controller.switchTo(CloudStorageType.GOOGLE_DRIVE)
        awaitConditionBlocking { controller.connectFailedType.value == CloudStorageType.GOOGLE_DRIVE }

        assertNull(controller.connectedType.value)
        assertEquals(CloudStorageType.GOOGLE_DRIVE, controller.connectFailedType.value)
        assertNull(controller.connectingType.value)
        // The Dropbox disconnect already happened (and is irreversible) before the Google Drive
        // connect attempt was made and failed — this is intentional per switchTo's design, not a bug.
        assertNull(dropboxTokenStorage.load())
        assertNull(googleDriveTokenStorage.load())
    }

    @Test
    fun lastSyncedAtTextReflectsSyncStateOnInit() {
        val expectedMillis = 1_234_567_890_123L
        db.sync_stateQueries.upsert(SYNC_STATE_LAST_SYNCED_AT, expectedMillis.toString())

        val controller = newController()

        awaitConditionBlocking { controller.lastSyncedAtText.value == formatTimestamp(expectedMillis) }
    }

    /**
     * Constructing the controller (which Home's ViewModel depends on, on the UI thread) must not
     * read the last-synced time synchronously: the initial read runs on the controller's
     * `dispatcher`, from the activity collector's subscription replay, and fills
     * [CloudSyncController.lastSyncedAtText] once it gets there.
     *
     * Note: avoids `runTest`'s virtual scheduler for the same reason as
     * disconnectClearsConnectedTypeAndCloudStorageType above.
     */
    @Test
    fun lastSyncedAtTextIsFilledAfterConstructionOffTheConstructingThread() {
        val expectedMillis = 1_234_567_890_123L
        db.sync_stateQueries.upsert(SYNC_STATE_LAST_SYNCED_AT, expectedMillis.toString())
        val dispatcher = HoldingDispatcher()

        val controller = newController(dispatcher = dispatcher)
        // The read is queued on the (held) dispatcher, not done inline during construction.
        assertNull(controller.lastSyncedAtText.value)

        dispatcher.release()

        awaitConditionBlocking { controller.lastSyncedAtText.value == formatTimestamp(expectedMillis) }
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler, same reason as
    // disconnectClearsConnectedTypeAndCloudStorageType above — the controller's reactive collector
    // runs on the standalone UnconfinedTestDispatcher installed as Main in setUp(), not on
    // runTest's own TestCoroutineScheduler, so we poll with real wall-clock waits instead.
    @Test
    fun lastSyncedAtTextRefreshesWhenActivityCenterReportsSyncCompletion() {
        val activityCenter = ActivityCenter()
        val controller = newController(activityCenter = activityCenter)
        assertNull(controller.lastSyncedAtText.value)

        // Simulate what SyncRepository.sync() does on success: write the new sync_state row, then
        // report a sync cycle through the same ActivityCenter the controller observes. A short real
        // delay (every real sync does at least one suspending network call) gives the controller's
        // collector — a StateFlow collector is conflated, so it can miss a value that is replaced
        // straight away — a chance to actually observe the true state before it flips back to false.
        val newMillis = 1_234_567_890_123L
        db.sync_stateQueries.upsert(SYNC_STATE_LAST_SYNCED_AT, newMillis.toString())
        runBlocking { activityCenter.trackSync { delay(50) } }

        awaitConditionBlocking { controller.lastSyncedAtText.value == formatTimestamp(newMillis) }
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler, same reason as
    // disconnectClearsConnectedTypeAndCloudStorageType above.
    @Test
    fun disconnectClearsLastSyncedAtText() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        db.sync_stateQueries.upsert(SYNC_STATE_LAST_SYNCED_AT, "1234567890123")
        val controller = newController(tokenStorage = tokenStorage)
        assertEquals(CloudStorageType.DROPBOX, controller.connectedType.value)
        awaitConditionBlocking { controller.lastSyncedAtText.value != null }

        controller.disconnect()
        awaitConditionBlocking { controller.connectedType.value == null }

        assertNull(controller.lastSyncedAtText.value)
    }

    /**
     * The cloud-sync tab swaps its reset action for a reconnect one off this flag, so it has to
     * reach the controller at all — it travels on its own collector, separate from the one carrying
     * [CloudSyncController.lastSyncError].
     *
     * Note: avoids `runTest`'s virtual scheduler for the same reason as
     * disconnectClearsConnectedTypeAndCloudStorageType above.
     */
    @Test
    fun lastSyncAuthFailedMirrorsSyncRepositoryFlag() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        val cloud = AlwaysFailingCloudStorage()
        val controller = newController(tokenStorage = tokenStorage, syncCloudProvider = { cloud })

        runBlocking { createdSyncRepository.sync() }

        awaitConditionBlocking { controller.lastSyncAuthFailed.value }
        assertTrue(controller.lastSyncAuthFailed.value)
    }

    /**
     * [CloudSyncController.connected] — the shared [works.merc.keryx.app.presentation.ManualSync.connected]
     * that decides whether every UI shows "Sync now" at all — follows this controller's own connect
     * and disconnect.
     *
     * Note: avoids `runTest`'s virtual scheduler for the same reason as
     * disconnectClearsConnectedTypeAndCloudStorageType above.
     */
    @Test
    fun connectedFollowsConnectAndDisconnect() {
        val controller = newController(connectResult = Result.Ok(OAuthTokens("AT")))
        assertFalse(controller.connected.value)

        controller.connect(CloudStorageType.DROPBOX)
        awaitConditionBlocking { controller.connected.value }
        assertEquals(CloudStorageType.DROPBOX, controller.connectedType.value)

        controller.disconnect()
        awaitConditionBlocking { !controller.connected.value }
        assertNull(controller.connectedType.value)
    }

    /**
     * [CloudSyncController.disabledByAuth] is the one "why is Sync now disabled" decision every UI
     * reads; it turns on after a sync fails on authorization, and "sync now" is then disabled too.
     *
     * Note: avoids `runTest`'s virtual scheduler for the same reason as
     * disconnectClearsConnectedTypeAndCloudStorageType above.
     */
    @Test
    fun disabledByAuthIsTrueAfterAnAuthFailedSyncAndCanSyncNowIsFalse() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        val cloud = AlwaysFailingCloudStorage()
        val controller = newController(tokenStorage = tokenStorage, syncCloudProvider = { cloud })
        assertFalse(controller.disabledByAuth.value)
        assertTrue(controller.canSyncNow.value)

        runBlocking { createdSyncRepository.sync() }

        awaitConditionBlocking { controller.disabledByAuth.value }
        assertFalse(controller.canSyncNow.value)
    }

    @Test
    fun canSyncNowIsFalseWithNoProviderConnected() {
        val controller = newController()
        assertNull(controller.connectedType.value)

        assertFalse(controller.canSyncNow.value)
    }

    /**
     * A provider connected by code other than this controller — the first-run setup screen's
     * `SetupController`, which on desktop runs while this controller already exists — must still
     * reach [CloudSyncController.connectedType] and [CloudSyncController.canSyncNow] without a
     * restart, and so must a disconnect made the same way.
     *
     * Note: avoids `runTest`'s virtual scheduler for the same reason as syncingMirrorsActivityCenter.
     */
    @Test
    fun providerChangeMadeOutsideTheControllerUpdatesConnectedTypeAndCanSyncNow() {
        val tokenStorage = FakeTokenStorage()
        val authClient = okAuthClient()
        val session = singleProviderCloudSession(
            client = authClient,
            tokenStorage = tokenStorage,
            authManager = DropboxAuthManager(authClient, clock = { 0L }),
        )
        val controller = newController(cloudSession = session)
        assertNull(controller.connectedType.value)
        assertFalse(controller.canSyncNow.value)

        // What SetupController.connect does once OAuth succeeds, through its own service instance.
        val outside = CloudConnectionService(session, createdSettingsRepository, createdSyncRepository)
        runBlocking { outside.completeConnect(CloudStorageType.DROPBOX, OAuthTokens("AT")) }

        awaitConditionBlocking { controller.connectedType.value == CloudStorageType.DROPBOX }
        assertTrue(controller.canSyncNow.value)

        runBlocking { outside.tearDown(CloudStorageType.DROPBOX) }

        awaitConditionBlocking { controller.connectedType.value == null }
        assertFalse(controller.canSyncNow.value)
    }

    /**
     * The cloud-sync tab's "sync now" follows Home's cloud button: enabled only while nothing else
     * is running, including a sync this controller didn't start.
     *
     * Note: avoids `runTest`'s virtual scheduler for the same reason as syncingMirrorsActivityCenter.
     */
    @Test
    fun canSyncNowTracksActivityCenterIdleWhileConnected() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        val activityCenter = ActivityCenter()
        val controller = newController(tokenStorage = tokenStorage, activityCenter = activityCenter)
        assertTrue(controller.canSyncNow.value)

        val gate = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            activityCenter.trackSync { gate.await() }
        }
        awaitConditionBlocking { !controller.idle.value }
        assertFalse(controller.canSyncNow.value)

        gate.complete(Unit)
        awaitConditionBlocking { controller.idle.value }
        assertTrue(controller.canSyncNow.value)
        runBlocking { job.join() }
    }

    /**
     * An authorization failure disables "sync now": a sync would only repeat it, and the row's own
     * "reconnect" is what fixes it.
     *
     * Note: avoids `runTest`'s virtual scheduler for the same reason as
     * disconnectClearsConnectedTypeAndCloudStorageType above.
     */
    @Test
    fun canSyncNowIsFalseWhileLastSyncFailedOnAuthorization() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        val cloud = AlwaysFailingCloudStorage()
        val controller = newController(tokenStorage = tokenStorage, syncCloudProvider = { cloud })
        assertTrue(controller.canSyncNow.value)

        runBlocking { createdSyncRepository.sync() }

        awaitConditionBlocking { controller.lastSyncAuthFailed.value }
        assertFalse(controller.canSyncNow.value)
    }

    /**
     * `syncNow()` runs a real sync through [SyncRepository] — observable as the sync the controller
     * mirrors — and the button disables itself for as long as that sync runs.
     *
     * Note: avoids `runTest`'s virtual scheduler for the same reason as syncingMirrorsActivityCenter.
     */
    @Test
    fun syncNowRunsASyncAndDisablesItselfUntilItFinishes() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        val gate = CompletableDeferred<Unit>()
        val controller = newController(
            tokenStorage = tokenStorage,
            syncCloudProvider = { GatedCloudStorage(gate) },
            dispatcher = Dispatchers.Default,
        )
        assertFalse(controller.syncing.value)

        controller.syncNow()

        // Two separate collectors carry these, so wait on each rather than assume their order.
        awaitConditionBlocking { controller.syncing.value }
        awaitConditionBlocking { controller.syncPhase.value == SyncPhase.CHECKING }
        assertFalse(controller.canSyncNow.value)

        gate.complete(Unit)
        awaitConditionBlocking { !controller.syncing.value }
        assertFalse(controller.syncing.value)
    }

    /**
     * [CloudSyncController.runs] brackets every `syncNow()` with Started then Finished — what Home
     * re-trims its pinned rows on, whichever route started the sync.
     *
     * Note: avoids `runTest`'s virtual scheduler for the same reason as syncingMirrorsActivityCenter.
     */
    @Test
    fun syncNowEmitsStartedThenFinished() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        val controller = newController(tokenStorage = tokenStorage)
        val edges = java.util.concurrent.CopyOnWriteArrayList<ManualSyncEdge>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { controller.runs.collect { edges += it } }
        try {
            controller.syncNow()

            awaitConditionBlocking { edges.size == 2 }
            assertEquals(listOf(ManualSyncEdge.Started, ManualSyncEdge.Finished), edges.toList())
        } finally {
            runBlocking { collector.cancelAndJoin() }
        }
    }

    /** A failing sync still ends with Finished, so a collector is never left mid-run. */
    @Test
    fun syncNowEmitsFinishedEvenWhenTheSyncFails() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        val cloud = AlwaysFailingCloudStorage()
        val controller = newController(tokenStorage = tokenStorage, syncCloudProvider = { cloud })
        val edges = java.util.concurrent.CopyOnWriteArrayList<ManualSyncEdge>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { controller.runs.collect { edges += it } }
        try {
            controller.syncNow()

            awaitConditionBlocking { edges.size == 2 }
            assertEquals(listOf(ManualSyncEdge.Started, ManualSyncEdge.Finished), edges.toList())
            awaitConditionBlocking { controller.lastSyncAuthFailed.value }
        } finally {
            runBlocking { collector.cancelAndJoin() }
        }
    }

    /** A `syncNow()` that its guard turns away starts nothing, so it emits no edge either. */
    @Test
    fun syncNowEmitsNothingWhenItCannotSync() {
        val controller = newController()
        assertFalse(controller.canSyncNow.value)
        val edges = java.util.concurrent.CopyOnWriteArrayList<ManualSyncEdge>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { controller.runs.collect { edges += it } }
        try {
            controller.syncNow()

            assertTrue(edges.isEmpty())
        } finally {
            runBlocking { collector.cancelAndJoin() }
        }
    }

    /**
     * A second `syncNow()` while the first is still in flight must be ignored. Without a local
     * in-flight flag the second call can race past [CloudSyncController.canSyncNow] before the
     * [ActivityCenter] collector updates [CloudSyncController.idle], causing a redundant sync to
     * queue behind [SyncRepository]'s mutex. A [HoldingDispatcher] holds the sync body so the
     * second call lands while `idle` is still true — the in-flight flag, not the idle gate, must be
     * what rejects it.
     *
     * Note: avoids `runTest`'s virtual scheduler for the same reason as syncingMirrorsActivityCenter.
     */
    @Test
    fun syncNowIgnoresSecondCallWhileFirstIsInFlight() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        val gate = CompletableDeferred<Unit>()
        val metadataCalls = AtomicInteger()
        val held = HoldingDispatcher()
        val controller = newController(
            tokenStorage = tokenStorage,
            syncCloudProvider = { GatedCloudStorage(gate, metadataCalls) },
            dispatcher = held,
        )
        assertTrue(controller.canSyncNow.value)

        controller.syncNow()
        try {
            // The sync body is held, so ActivityCenter hasn't seen it: idle is still true and only
            // the in-flight flag can be what disables the button.
            assertTrue(controller.idle.value)
            assertFalse(controller.canSyncNow.value)

            controller.syncNow() // must be ignored by the in-flight flag, not by idle
        } finally {
            // Released even when an assertion above fails: a block still held can never resume,
            // so tearDown()'s cancelAndJoin would wait on it forever.
            held.release()
            gate.complete(Unit)
        }
        awaitConditionBlocking { controller.canSyncNow.value }
        // One sync's worth; a second sync queued behind the mutex would double this.
        assertEquals(2, metadataCalls.get())
    }

    /**
     * `reconnect()` must not stop at the teardown half. On Android's Google Drive the disconnect is
     * the only thing that clears Play services' cached token, but a disconnect that never connects
     * back would leave the user staring at an unconfigured provider after pressing a button labelled
     * "reconnect".
     *
     * Note: avoids `runTest`'s virtual scheduler for the same reason as
     * disconnectClearsConnectedTypeAndCloudStorageType above.
     */
    @Test
    fun reconnectTearsDownAndConnectsBackToTheSameProvider() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        val cloud = AlwaysFailingCloudStorage()
        // Holds the reconnect's authorization so the teardown is observable on its own: the test
        // starts on Dropbox, so awaiting Dropbox alone would pass even if reconnect() did nothing.
        val authorization = CompletableDeferred<Result<OAuthTokens>>()
        val connectFlow = object : CloudConnectFlow {
            override suspend fun connect(): Result<OAuthTokens> = authorization.await()
        }
        val controller = newController(tokenStorage = tokenStorage, connectFlow = connectFlow, syncCloudProvider = { cloud })
        runBlocking { createdSyncRepository.sync() }
        awaitConditionBlocking { controller.lastSyncAuthFailed.value }

        controller.reconnect()

        // Torn down first; nothing can set it back while the authorization is held.
        awaitConditionBlocking { controller.connectedType.value == null }
        authorization.complete(Result.Ok(OAuthTokens("AT2")))

        // Back on the same provider — connect re-set it. The await is the assertion: re-reading the
        // value afterwards could catch a transient write from the settings watcher, which this
        // test's multi-threaded Unconfined Main lets interleave.
        awaitConditionBlocking { controller.connectedType.value == CloudStorageType.DROPBOX }
    }

    /**
     * A provider re-read that finished for a selection already superseded must not land. The window
     * collectLatest cannot close: the read completes (its resumption already queued on Main) before
     * the newer selection is persisted, so nothing cancels it. A StandardTestDispatcher Main holds
     * both resumptions until the test runs them, in that order.
     */
    @Test
    fun providerReadFinishedForASupersededSelectionIsNotApplied() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val readDispatcher = HoldingDispatcher()
        val session = multiProviderCloudSession(
            client = okAuthClient(),
            dropboxTokenStorage = FakeTokenStorage(OAuthTokens("AT")),
            googleDriveTokenStorage = FakeTokenStorage(OAuthTokens("AT2")),
            selectedType = settingsBackedSelection(CloudStorageType.DROPBOX),
        )
        val controller = newController(cloudSession = session, dispatcher = readDispatcher)
        val history = mutableListOf<CloudStorageType?>()
        val recorder = CoroutineScope(Dispatchers.Unconfined).launch { controller.connectedType.collect { history += it } }
        try {
            main.scheduler.runCurrent()

            // The watcher starts re-reading for Google Drive; the read itself is held.
            createdSettingsRepository.saveLocalSettings(
                createdSettingsRepository.getLocalSettings().copy(cloudStorageType = CloudStorageType.GOOGLE_DRIVE.id),
            )
            main.scheduler.runCurrent()
            // The read completes (Google Drive), queuing its resumption on Main...
            readDispatcher.release()
            // ...and only then does the selection move on, before Main has run anything.
            createdSettingsRepository.saveLocalSettings(createdSettingsRepository.getLocalSettings().copy(cloudStorageType = null))
            main.scheduler.advanceUntilIdle()

            // Google Drive differs from both the initial and the final value, so it can only appear in
            // the history through the stale read.
            assertEquals(listOf(CloudStorageType.DROPBOX, null), history)
        } finally {
            // Cancel while Main can still be driven, pass or fail: tearDown()'s cancelAndJoin would
            // otherwise wait forever on cancellation work queued on a dispatcher nothing runs any more.
            recorder.cancel()
            controller.viewModelScope.cancel()
            main.scheduler.advanceUntilIdle()
        }
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler, same reason as
    // disconnectClearsConnectedTypeAndCloudStorageType above.
    @Test
    fun disconnectClearsLastSyncErrorText() {
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        val cloud = AlwaysFailingCloudStorage()
        val controller = newController(tokenStorage = tokenStorage, syncCloudProvider = { cloud })
        runBlocking { createdSyncRepository.sync() }
        awaitConditionBlocking { controller.lastSyncError.value == ErrorKind.CLOUD_AUTH }

        controller.disconnect()
        // Await the actual condition being asserted, not just connectedType: lastSyncError is
        // updated by an independent collector coroutine (init block) reacting to
        // clearSyncFailureState()'s StateFlow write, so polling connectedType alone gives no
        // happens-before guarantee for it.
        awaitConditionBlocking { controller.connectedType.value == null && controller.lastSyncError.value == null }

        assertNull(controller.lastSyncError.value)
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
            selectedType = settingsBackedSelection(CloudStorageType.DROPBOX),
            // Blocks indefinitely on OAuth, so the new provider's own connect/sync never runs —
            // isolating the fix (clearing on disconnect) from a later successful sync also clearing it.
            googleDriveConnectFlow = SuspendingCloudConnectFlow(),
        )
        val cloud = AlwaysFailingCloudStorage()
        val controller = newController(cloudSession = session, syncCloudProvider = { cloud })
        runBlocking { createdSyncRepository.sync() }
        awaitConditionBlocking { controller.lastSyncError.value == ErrorKind.CLOUD_AUTH }

        controller.switchTo(CloudStorageType.GOOGLE_DRIVE)
        // connectingType flips to GOOGLE_DRIVE synchronously at the top of switchTo(), before the old
        // provider is even disconnected — wait for canCancelConnect instead, which only becomes true
        // once connect(newType) is underway (i.e. after clearSyncFailureState() has already run). Also
        // await lastSyncError directly: it's updated by an independent collector coroutine
        // reacting to clearSyncFailureState()'s StateFlow write, so canCancelConnect alone gives no
        // happens-before guarantee for it (see disconnectClearsLastSyncErrorText for the same race).
        awaitConditionBlocking { controller.canCancelConnect.value && controller.lastSyncError.value == null }

        assertNull(controller.lastSyncError.value)
    }

    // Note: this test deliberately avoids `runTest`'s virtual scheduler, same reason as
    // disconnectClearsConnectedTypeAndCloudStorageType above.
    @Test
    fun lastSyncErrorMirrorsSyncRepositoryLastSyncError() {
        // The cloud-sync tab shows this as the reason the connected provider isn't syncing, so it
        // has to track SyncRepository.lastSyncError in both directions.
        val tokenStorage = FakeTokenStorage()
        tokenStorage.save(OAuthTokens("AT"))
        val cloud = AlwaysFailingCloudStorage()
        var failing = true
        val controller = newController(tokenStorage = tokenStorage, syncCloudProvider = { if (failing) cloud else null })
        assertNull(controller.lastSyncError.value)

        runBlocking { createdSyncRepository.sync() }
        awaitConditionBlocking { controller.lastSyncError.value == ErrorKind.CLOUD_AUTH }

        // Local-only from here on, so the next sync is a success and must clear the reason.
        failing = false
        runBlocking { createdSyncRepository.sync() }
        awaitConditionBlocking { controller.lastSyncError.value == null }
    }

}
