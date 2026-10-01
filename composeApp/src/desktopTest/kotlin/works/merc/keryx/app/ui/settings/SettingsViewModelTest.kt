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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.jetbrains.compose.resources.getString
import works.merc.keryx.app.FakeCloudConnectFlow
import works.merc.keryx.app.FakeTokenStorage
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.opml.OpmlCodec
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.data.remote.UpdateDownloader
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.domain.CloudConnectionService
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.InstallLaunchResult
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.OpmlImporter
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.domain.TagRepository
import works.merc.keryx.app.domain.UpdateChecker
import works.merc.keryx.app.domain.UpdateInstaller
import works.merc.keryx.app.domain.UpdatePlan
import works.merc.keryx.app.domain.UpdateRepository
import works.merc.keryx.app.domain.UpdateState
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.platform.FileSelector
import works.merc.keryx.app.platform.OpenFileRequest
import works.merc.keryx.app.platform.PathPickedFile
import works.merc.keryx.app.platform.PickedFile
import works.merc.keryx.app.platform.SaveFileRequest
import works.merc.keryx.app.presentation.settings.CloudSyncController
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import works.merc.keryx.app.presentation.settings.OpmlOperation
import works.merc.keryx.app.presentation.settings.OpmlRequest
import works.merc.keryx.app.presentation.settings.OpmlResult
import works.merc.keryx.app.presentation.settings.OpmlTransfer
import works.merc.keryx.app.presentation.settings.OpmlTransferController
import works.merc.keryx.app.presentation.settings.PreferencesController
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.settings_export_opml
import works.merc.keryx.app.singleProviderCloudSession
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

    var openCount = 0
        private set

    override suspend fun pickOpenFile(request: OpenFileRequest): PickedFile? {
        lastOpenRequest = request
        openCount++
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
 * `dispatch()` is invoked directly.
 */
private class CountingDispatcher : CoroutineDispatcher() {
    var dispatchCount = 0
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchCount++
        block.run()
    }
}

/**
 * Holds every dispatched block until [runQueued], so a test can observe what was launched before
 * any of it has run — e.g. that two back-to-back calls launched only one coroutine.
 */
private class QueueingDispatcher : CoroutineDispatcher() {
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
    val queuedCount: Int get() = queue.size

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.add(block)
    }

    fun runQueued() {
        while (true) (queue.poll() ?: return).run()
    }
}

/** Throws [CancellationException] the moment work is dispatched to it — simulates the coroutine being cancelled mid-`withContext`. */
private class CancellingDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        throw CancellationException("cancelled for test")
    }
}

/**
 * [SettingsViewModel] is now a thin wrapper around [CloudSyncController] / [PreferencesController]
 * / [OpmlTransfer] (each covered by its own test in `:shared`) plus the two things that stay
 * Compose/desktop-only: the in-app updater and OPML file picking. This suite therefore covers only
 * those two areas, plus a couple of delegation smoke tests confirming the wrapper actually forwards
 * to the controllers rather than reimplementing their logic.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: KeryxDatabase
    private val dir = FileIO.join(AppDirs.tempDir(), "settings-vm-test-${Random.nextInt()}")

    // ViewModels created via newViewModel(). Their viewModelScope is not tied to runTest's scope,
    // so it must be cancelled explicitly before driver.close() — otherwise in-flight coroutines
    // outlive the test and can throw against the closed driver, surfacing (flakily, on another
    // test) as kotlinx.coroutines.test.UncaughtExceptionsBeforeTest. The wrapped CloudSyncController
    // has its own separate viewModelScope and must be cancelled too.
    private val createdViewModels = mutableListOf<SettingsViewModel>()
    private val createdCloudSyncControllers = mutableListOf<CloudSyncController>()
    private val createdSyncScopes = mutableListOf<CoroutineScope>()

    /** The [OpmlTransferController] behind the last [newViewModel] — what the File menu would call. */
    private lateinit var lastOpmlController: OpmlTransferController

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val (d, database) = inMemoryDb()
        driver = d
        db = database
    }

    @AfterTest
    fun tearDown() {
        runBlocking {
            createdViewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
            createdCloudSyncControllers.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
            createdSyncScopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        }
        createdViewModels.clear()
        createdCloudSyncControllers.clear()
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
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { createdSyncScopes += it },
        )
    }

    private fun newViewModel(
        updateRepository: UpdateRepository = fakeUpdateRepository(updateCheckerReturning("1.0.0")),
        // Only the OPML import tests need subscribeFeed to actually succeed.
        feedFetcher: FeedFetcher = failingFetcher(),
        // Default cancels every OPML pick — a test exercising import/export supplies its own.
        fileSelector: FileSelector = FakeFileSelector(),
        // Passed to the VM's own `dispatcher` (blocking OPML build/write/import work). Default
        // matches the rest of newViewModel's Unconfined setup; a test can supply a CountingDispatcher
        // to assert it was actually used.
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): SettingsViewModel {
        val clock = Clock { 0L }
        val syncScheduler = SyncScheduler {}
        val articleRepository = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)
        // Mirror startup: ensureIndexed() creates articles_fts so subscribeFeed's indexMissing() works.
        val ftsManager = ftsManagerIndexed(driver)
        val feedRepository = FeedRepository(
            db, feedFetcher, missingFaviconResolver(), articleRepository, ftsManager, syncScheduler,
            NotificationCenter(), clock, Dispatchers.Unconfined,
        )
        val folderRepository = FolderRepository(db, feedRepository, syncScheduler, clock, Dispatchers.Unconfined)
        val tagRepository = TagRepository(db, syncScheduler, clock, Dispatchers.Unconfined)
        val opmlImporter = OpmlImporter(feedRepository, folderRepository, tagRepository)
        val opmlTransfer = OpmlTransfer(feedRepository, folderRepository, tagRepository, opmlImporter)
        val opmlController = OpmlTransferController(opmlTransfer, Dispatchers.Unconfined).also { lastOpmlController = it }
        // Unconfined write dispatcher so saveLocalSettings persists inline.
        val settingsRepository =
            SettingsRepository(db, LocalSettingsStore(dirOverride = dir), syncScheduler, clock, writeDispatcher = Dispatchers.Unconfined)
        val preferencesController = PreferencesController(settingsRepository)
        val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        createdSyncScopes += syncScope
        val syncRepository = SyncRepository(
            driver = driver,
            db = db,
            ftsManager = FtsManager(driver),
            cloudProvider = { null },
            clock = clock,
            scope = syncScope,
            activityCenter = ActivityCenter(),
            notificationCenter = NotificationCenter(),
            localDbPath = "unused",
            tempDir = "unused",
        )
        val authClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { expectSuccess = false }
        val authManager = DropboxAuthManager(authClient, clock = clock)
        val cloudSession = singleProviderCloudSession(
            client = authClient,
            tokenStorage = FakeTokenStorage(),
            authManager = authManager,
            clock = clock,
            connectFlow = FakeCloudConnectFlow(Result.Ok(OAuthTokens("AT"))),
        )
        val cloudSyncController = CloudSyncController(
            cloudSession, syncRepository, CloudConnectionService(cloudSession, settingsRepository, syncRepository), ActivityCenter(),
            settingsRepository,
            // Unconfined so connectDelegatesToCloudSyncController's testScheduler.advanceUntilIdle()
            // actually observes the token-save/sync hop, rather than it landing on a real thread.
            Dispatchers.Unconfined,
        ).also { createdCloudSyncControllers += it }
        return SettingsViewModel(
            cloudSyncController, preferencesController, opmlController, updateRepository, dispatcher, fileSelector,
        ).also { createdViewModels += it }
    }

    // --- Delegation smoke tests: the wrapper must actually forward to its controllers ---

    @Test
    fun setThemeModeDelegatesToPreferencesControllerAndPersists() {
        val store = LocalSettingsStore(dirOverride = dir)
        val vm = newViewModel()

        vm.setThemeMode("dark")

        assertEquals("dark", vm.localSettings.value.themeMode)
        assertEquals("dark", store.load().themeMode)
    }

    @Test
    fun connectDelegatesToCloudSyncController() = runTest {
        val vm = newViewModel()
        assertNull(vm.connectedType.value)

        vm.connect(CloudStorageType.DROPBOX)
        testScheduler.advanceUntilIdle()

        assertEquals(CloudStorageType.DROPBOX, vm.connectedType.value)
    }

    // --- In-app update ---

    // Note: this test deliberately avoids `runTest`'s virtual scheduler — UpdateChecker makes a
    // real (mocked) HTTP call whose completion is dispatched on a real thread outside the
    // TestCoroutineScheduler, so we poll with real wall-clock waits instead.
    @Test
    fun checkForUpdateSurfacesAvailableResultWithoutTouchingLastUpdateCheckAt() {
        val vm = newViewModel(updateRepository = fakeUpdateRepository(updateCheckerReturning("2.0.0")))
        assertEquals(UpdateState.Idle, vm.updateState.value)

        vm.checkForUpdate()
        awaitTrue { vm.updateState.value is UpdateState.Available }

        val result = vm.updateState.value
        assertIs<UpdateState.Available>(result)
        assertEquals("2.0.0", result.update.version)
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
        awaitTrue { vm.updateState.value is UpdateState.Checking }
        vm.checkForUpdate() // ignored: a check is already in flight

        awaitTrue { vm.updateState.value is UpdateState.Available }
        assertEquals(1, requestCount)
    }

    /**
     * The tray/Help menu's update entry runs a check and opens the Updates tab, whose own
     * check-on-open then calls [SettingsViewModel.checkForUpdate] again — before the first check has
     * had a chance to reach [UpdateState.Checking]. The second call must still be a no-op.
     */
    @Test
    fun checkForUpdateIgnoresASecondCallBeforeTheFirstHasStarted() {
        var requestCount = 0
        val client = HttpClient(
            MockEngine {
                requestCount++
                respond(
                    """{"tag_name":"2.0.0","html_url":"https://ex.com/releases/2.0.0","prerelease":false,"draft":false}""",
                    HttpStatusCode.OK,
                )
            },
        ) { expectSuccess = false }
        val dispatcher = QueueingDispatcher()
        val vm = newViewModel(
            updateRepository = fakeUpdateRepository(UpdateChecker(client, currentVersion = "1.0.0", repoSlug = "owner/repo")),
            dispatcher = dispatcher,
        )

        vm.checkForUpdate()
        vm.checkForUpdate()

        assertEquals(UpdateState.Idle, vm.updateState.value, "neither check has started running yet")
        assertEquals(1, dispatcher.queuedCount, "only one check may be launched")
        awaitTrue {
            dispatcher.runQueued()
            vm.updateState.value is UpdateState.Available
        }
        assertEquals(1, requestCount)

        // Once it has finished, the guard is released: a later check runs again.
        vm.checkForUpdate()
        awaitTrue {
            dispatcher.runQueued()
            requestCount == 2
        }
    }

    // --- OPML (file picking + busy state) ---

    @Test
    fun exportOpmlWritesTheBuiltDocumentToThePickedPath() = runTest {
        db.insertFeed("f1", url = "https://a.com/feed")
        val path = FileIO.join(dir, "export.opml")
        val vm = newViewModel(fileSelector = FakeFileSelector(savePath = path))

        vm.exportOpml()

        // The write runs on the injected (non-test) dispatcher, so it's a real, non-virtual hop.
        awaitTrue { vm.opmlResult.value != null }
        assertEquals(OpmlResult.Exported, vm.opmlResult.value)
        val written = FileIO.readText(path)
        assertNotNull(written)
        assertEquals(listOf("https://a.com/feed"), OpmlCodec.import(written).map { it.xmlUrl })
    }

    @Test
    fun exportOpmlLeavesResultNullWhenTheDialogIsDismissed() = runTest {
        val vm = newViewModel(fileSelector = FakeFileSelector(savePath = null))

        vm.exportOpml()

        assertNull(vm.opmlResult.value)
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

        awaitTrue { vm.opmlResult.value != null }
        assertEquals(OpmlResult.Exported, vm.opmlResult.value)
        assertTrue(counting.dispatchCount > 0)
    }

    @Test
    fun exportOpmlReportsFailedWhenTheWriteThrows() = runTest {
        val blockingFile = FileIO.join(dir, "not-a-directory")
        FileIO.writeText(blockingFile, "x")
        val path = FileIO.join(blockingFile, "export.opml")
        val vm = newViewModel(fileSelector = FakeFileSelector(savePath = path))

        vm.exportOpml()

        awaitTrue { vm.opmlResult.value != null }
        assertEquals(OpmlResult.ExportFailed, vm.opmlResult.value)
    }

    @Test
    fun exportOpmlRethrowsCancellationInsteadOfReportingFailure() = runTest {
        val path = FileIO.join(dir, "export-cancel.opml")
        val vm = newViewModel(fileSelector = FakeFileSelector(savePath = path), dispatcher = CancellingDispatcher())

        vm.exportOpml()

        assertNull(vm.opmlResult.value)
        assertFalse(vm.opmlBusy.value)
    }

    @Test
    fun importOpmlSubscribesEveryFeedInThePickedFile() = runTest {
        val xml = """<opml><body><outline text="Feed" xmlUrl="https://ex.com/feed"/></body></opml>"""
        val path = FileIO.join(dir, "import.opml")
        FileIO.writeText(path, xml)
        val vm = newViewModel(feedFetcher = succeedingFetcher(), fileSelector = FakeFileSelector(openPath = path))

        vm.importOpml()

        // The read + import (network fetch, DB writes) run on the injected (non-test) dispatcher.
        awaitTrue { vm.opmlResult.value != null }
        val result = vm.opmlResult.value
        assertIs<OpmlResult.Imported>(result)
        assertEquals(1, result.added)
        assertEquals(0, result.failed)
    }

    @Test
    fun importOpmlLeavesResultNullWhenTheDialogIsDismissed() = runTest {
        val vm = newViewModel(fileSelector = FakeFileSelector(openPath = null))

        vm.importOpml()

        assertNull(vm.opmlResult.value)
    }

    @Test
    fun importOpmlReportsFailedWhenTheFileCannotBeRead() = runTest {
        val missingPath = FileIO.join(dir, "does-not-exist.opml")
        val vm = newViewModel(fileSelector = FakeFileSelector(openPath = missingPath))

        vm.importOpml()

        assertEquals(OpmlResult.ImportFailed, vm.opmlResult.value)
    }

    @Test
    fun importOpmlRethrowsCancellationInsteadOfReportingFailure() = runTest {
        val path = FileIO.join(dir, "import-cancel.opml")
        FileIO.writeText(path, "<opml><body/></opml>")
        val vm = newViewModel(fileSelector = FakeFileSelector(openPath = path), dispatcher = CancellingDispatcher())

        vm.importOpml()

        assertNull(vm.opmlResult.value)
        assertFalse(vm.opmlBusy.value)
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

        awaitTrue { vm.opmlResult.value != null }
        assertIs<OpmlResult.Imported>(vm.opmlResult.value)
        assertTrue(counting.dispatchCount > 0)
    }

    @Test
    fun importAndExportOpmlRefuseToRunConcurrently() = runTest {
        val selector = SuspendingFileSelector()
        val vm = newViewModel(fileSelector = selector)

        vm.importOpml()
        assertEquals(OpmlOperation.Importing, vm.opmlRunning.value)
        assertTrue(vm.opmlBusy.value, "busy from the moment the file dialog opens")

        // Guarded no-op: the import is still running, so this must not start an export.
        vm.exportOpml()
        assertEquals(OpmlOperation.Importing, vm.opmlRunning.value)

        selector.openDeferred.complete(null)
        assertNull(vm.opmlResult.value)
        assertFalse(vm.opmlBusy.value)
    }

    @Test
    fun importDocumentImportsAnAlreadyReadDocument() = runTest {
        val xml = """<opml><body><outline text="Feed" xmlUrl="https://ex.com/feed"/></body></opml>"""
        val vm = newViewModel(feedFetcher = succeedingFetcher())

        vm.importDocument(xml)

        awaitTrue { vm.opmlResult.value != null }
        assertEquals(OpmlResult.Imported(added = 1, failed = 0), vm.opmlResult.value)
        assertFalse(vm.opmlBusy.value)
    }

    @Test
    fun importDocumentReportsFailedForAnUnreadableFile() = runTest {
        val vm = newViewModel()

        vm.importDocument(null)

        assertEquals(OpmlResult.ImportFailed, vm.opmlResult.value)
        assertFalse(vm.opmlBusy.value)
    }

    @Test
    fun startingANewOperationClearsThePreviousResult() = runTest {
        val selector = SuspendingFileSelector()
        val vm = newViewModel(fileSelector = selector)
        vm.importDocument(null)
        assertEquals(OpmlResult.ImportFailed, vm.opmlResult.value)

        vm.importOpml()

        assertNull(vm.opmlResult.value, "a stale result must not outlive the next operation's start")
        selector.openDeferred.complete(null)
    }

    @Test
    fun aPendingRequestIsHandedOutOnlyWhileNothingRuns() = runTest {
        val selector = SuspendingFileSelector()
        val vm = newViewModel(fileSelector = selector)
        vm.importOpml()

        lastOpmlController.request(OpmlRequest.ExportFile)
        assertNull(vm.consumeOpmlRequest(), "not consumable while the import runs")
        assertEquals(OpmlRequest.ExportFile, vm.pendingOpmlRequest.value)

        selector.openDeferred.complete(null)
        assertEquals(OpmlRequest.ExportFile, vm.consumeOpmlRequest())
        assertNull(vm.pendingOpmlRequest.value)
    }

    // --- DataTab carrying out a request from outside the tab (File menu, opened .opml file) ---

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun dataTabCarriesOutAPendingMenuImportExactlyOnce() {
        val selector = FakeFileSelector(openPath = null)
        val vm = newViewModel(fileSelector = selector)
        lastOpmlController.request(OpmlRequest.ImportFile)

        runDesktopComposeUiTest {
            setContent { DataTabContent(vm) }
            waitForIdle()
        }

        assertEquals(1, selector.openCount, "the file dialog opens from inside Settings ▸ Data, once")
        assertNull(vm.pendingOpmlRequest.value)
        assertFalse(vm.opmlBusy.value)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun dataTabLeavesAPendingRequestAloneWhileAnOperationRuns() {
        val selector = FakeFileSelector(openPath = null)
        val vm = newViewModel(fileSelector = selector)
        lastOpmlController.tryBegin(OpmlOperation.Exporting)
        lastOpmlController.request(OpmlRequest.ImportFile)

        runDesktopComposeUiTest {
            setContent { DataTabContent(vm) }
            waitForIdle()
        }

        assertEquals(0, selector.openCount)
        assertEquals(OpmlRequest.ImportFile, vm.pendingOpmlRequest.value)
    }

    /** Polls with real wall-clock waits (for coroutines that hop onto a real, non-virtual dispatcher). */
    private fun awaitTrue(timeoutMs: Long = 2_000, condition: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for condition" }
            delay(5)
        }
    }
}
