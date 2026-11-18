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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.jetbrains.compose.resources.getString
import works.merc.keryx.app.FakeCloudConnectFlow
import works.merc.keryx.app.FakeTokenStorage
import works.merc.keryx.app.HoldingDispatcher
import works.merc.keryx.app.InMemoryFaviconMissStore
import works.merc.keryx.app.awaitConditionBlocking
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

private const val ONE_FEED_OPML = """<opml><body><outline text="Feed" xmlUrl="https://ex.com/feed"/></body></opml>"""
private const val TWO_FEED_OPML =
    """<opml><body><outline text="A" xmlUrl="https://ex.com/a"/><outline text="B" xmlUrl="https://ex.com/b"/></body></opml>"""

/**
 * [SettingsViewModel] is now a thin wrapper around [CloudSyncController] / [PreferencesController]
 * / [OpmlTransfer] (each covered by its own test in `:shared`) plus the two things that stay
 * Compose/desktop-only: the in-app updater and OPML file picking. This suite therefore covers only
 * those two areas, plus a couple of delegation smoke tests confirming the wrapper actually forwards
 * to the controllers rather than reimplementing their logic.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var fx: SettingsViewModelFixture
    private val driver get() = fx.driver
    private val db get() = fx.db
    private val dir get() = fx.dir
    private val lastOpmlController get() = fx.lastOpmlController
    private val lastOpmlScope get() = fx.lastOpmlScope

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fx = SettingsViewModelFixture()
    }

    @AfterTest
    fun tearDown() {
        fx.cancelWork()
        Dispatchers.resetMain()
        fx.closeStorage()
    }


    // --- Delegation smoke tests: the wrapper must actually forward to its controllers ---

    @Test
    fun setThemeModeDelegatesToPreferencesControllerAndPersists() {
        val store = LocalSettingsStore(dirOverride = dir)
        val vm = fx.newViewModel()

        vm.setThemeMode("dark")

        assertEquals("dark", vm.localSettings.value.themeMode)
        assertEquals("dark", store.load().themeMode)
    }

    @Test
    fun connectDelegatesToCloudSyncController() = runTest {
        val vm = fx.newViewModel()
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
        val vm = fx.newViewModel(updateRepository = fx.fakeUpdateRepository(fx.updateCheckerReturning("2.0.0")))
        assertEquals(UpdateState.Idle, vm.updateState.value)

        vm.checkForUpdate()
        awaitConditionBlocking { vm.updateState.value is UpdateState.Available }

        val result = vm.updateState.value
        assertIs<UpdateState.Available>(result)
        assertEquals("2.0.0", result.update.version)
        // Manual checks are deliberately excluded from the automatic schedule (see SettingsViewModel).
        assertNull(vm.localSettings.value.lastUpdateCheckAt)
    }

    @Test
    fun checkForUpdateIgnoresOverlappingCallsWhileOneIsInFlight() {
        var requestCount = 0
        // Holds the first check in flight until the overlapping call has been made.
        val releaseResponse = CompletableDeferred<Unit>()
        val client = HttpClient(
            MockEngine {
                requestCount++
                releaseResponse.await()
                respond(
                    """{"tag_name":"2.0.0","html_url":"https://ex.com/releases/2.0.0","prerelease":false,"draft":false}""",
                    HttpStatusCode.OK,
                )
            },
        ) { expectSuccess = false }
        val vm = fx.newViewModel(updateRepository = fx.fakeUpdateRepository(UpdateChecker(client, currentVersion = "1.0.0", repoSlug = "owner/repo")))

        vm.checkForUpdate()
        awaitConditionBlocking { vm.updateState.value is UpdateState.Checking }
        vm.checkForUpdate() // ignored: a check is already in flight
        releaseResponse.complete(Unit)

        awaitConditionBlocking { vm.updateState.value is UpdateState.Available }
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
        val vm = fx.newViewModel(
            updateRepository = fx.fakeUpdateRepository(UpdateChecker(client, currentVersion = "1.0.0", repoSlug = "owner/repo")),
            dispatcher = dispatcher,
        )

        vm.checkForUpdate()
        vm.checkForUpdate()

        assertEquals(UpdateState.Idle, vm.updateState.value, "neither check has started running yet")
        assertEquals(1, dispatcher.queuedCount, "only one check may be launched")
        awaitConditionBlocking {
            dispatcher.runQueued()
            vm.updateState.value is UpdateState.Available
        }
        assertEquals(1, requestCount)

        // Once it has finished, the guard is released: a later check runs again.
        vm.checkForUpdate()
        awaitConditionBlocking {
            dispatcher.runQueued()
            requestCount == 2
        }
    }

    // --- OPML (file picking + busy state) ---

    @Test
    fun exportOpmlWritesTheBuiltDocumentToThePickedPath() = runTest {
        db.insertFeed("f1", url = "https://a.com/feed")
        val path = FileIO.join(dir, "export.opml")
        val vm = fx.newViewModel(fileSelector = FakeFileSelector(savePath = path))

        vm.exportOpml()

        // The write runs on the injected (non-test) dispatcher, so it's a real, non-virtual hop.
        awaitConditionBlocking { vm.opmlResult.value != null }
        assertEquals(OpmlResult.Exported, vm.opmlResult.value)
        val written = FileIO.readText(path)
        assertNotNull(written)
        assertEquals(listOf("https://a.com/feed"), OpmlCodec.import(written).map { it.xmlUrl })
    }

    @Test
    fun exportOpmlLeavesResultNullWhenTheDialogIsDismissed() = runTest {
        val vm = fx.newViewModel(fileSelector = FakeFileSelector(savePath = null))

        vm.exportOpml()

        assertNull(vm.opmlResult.value)
    }

    @Test
    fun exportOpmlPassesTheLocalizedTitleAndOverwriteLabelsToTheDialog() = runTest {
        val selector = FakeFileSelector(savePath = null)
        val vm = fx.newViewModel(fileSelector = selector)

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
        val vm = fx.newViewModel(fileSelector = FakeFileSelector(savePath = path), dispatcher = counting)

        vm.exportOpml()

        awaitConditionBlocking { vm.opmlResult.value != null }
        assertEquals(OpmlResult.Exported, vm.opmlResult.value)
        assertTrue(counting.dispatchCount > 0)
    }

    @Test
    fun exportOpmlReportsFailedWhenTheWriteThrows() = runTest {
        val blockingFile = FileIO.join(dir, "not-a-directory")
        FileIO.writeText(blockingFile, "x")
        val path = FileIO.join(blockingFile, "export.opml")
        val vm = fx.newViewModel(fileSelector = FakeFileSelector(savePath = path))

        vm.exportOpml()

        awaitConditionBlocking { vm.opmlResult.value != null }
        assertEquals(OpmlResult.ExportFailed, vm.opmlResult.value)
    }

    @Test
    fun exportOpmlRethrowsCancellationInsteadOfReportingFailure() = runTest {
        val path = FileIO.join(dir, "export-cancel.opml")
        val vm = fx.newViewModel(fileSelector = FakeFileSelector(savePath = path), dispatcher = CancellingDispatcher())

        vm.exportOpml()

        assertNull(vm.opmlResult.value)
        assertFalse(vm.opmlBusy.value)
    }

    @Test
    fun importOpmlSubscribesEveryFeedInThePickedFile() = runTest {
        val xml = """<opml><body><outline text="Feed" xmlUrl="https://ex.com/feed"/></body></opml>"""
        val path = FileIO.join(dir, "import.opml")
        FileIO.writeText(path, xml)
        val vm = fx.newViewModel(feedFetcher = fx.succeedingFetcher(), fileSelector = FakeFileSelector(openPath = path))

        vm.importOpml()

        // The read + import (network fetch, DB writes) run on the injected (non-test) dispatcher.
        awaitConditionBlocking { vm.opmlResult.value != null }
        val result = vm.opmlResult.value
        assertIs<OpmlResult.Imported>(result)
        assertEquals(1, result.added)
        assertEquals(0, result.failed)
    }

    @Test
    fun importOpmlLeavesResultNullWhenTheDialogIsDismissed() = runTest {
        val vm = fx.newViewModel(fileSelector = FakeFileSelector(openPath = null))

        vm.importOpml()

        assertNull(vm.opmlResult.value)
    }

    @Test
    fun importOpmlReportsFailedWhenTheFileCannotBeRead() = runTest {
        val missingPath = FileIO.join(dir, "does-not-exist.opml")
        val vm = fx.newViewModel(fileSelector = FakeFileSelector(openPath = missingPath))

        vm.importOpml()

        assertEquals(OpmlResult.ImportFailed, vm.opmlResult.value)
    }

    @Test
    fun importOpmlRethrowsCancellationInsteadOfReportingFailure() = runTest {
        val path = FileIO.join(dir, "import-cancel.opml")
        FileIO.writeText(path, "<opml><body/></opml>")
        val vm = fx.newViewModel(fileSelector = FakeFileSelector(openPath = path), dispatcher = CancellingDispatcher())

        vm.importOpml()

        assertNull(vm.opmlResult.value)
        assertFalse(vm.opmlBusy.value)
    }

    @Test
    fun cancellingTheViewModelKeepsOpmlBusyUntilTheImportHasStopped() {
        val path = FileIO.join(dir, "import-vm-cancel.opml")
        FileIO.writeText(path, ONE_FEED_OPML)
        val started = CompletableDeferred<Unit>()
        val importDispatcher = HoldingDispatcher()
        val vm = fx.newViewModel(
            feedFetcher = fx.gatedSucceedingFetcher(gate = CompletableDeferred(), started = started),
            fileSelector = FakeFileSelector(openPath = path),
            opmlImportDispatcher = importDispatcher,
        )

        vm.importOpml()
        awaitConditionBlocking { started.isCompleted }
        assertEquals(OpmlOperation.Importing, vm.opmlRunning.value)
        val import = lastOpmlScope.coroutineContext.job.children.single()

        // From here on, the import cannot finish (not even its own cancellation) until released.
        importDispatcher.hold()
        // Released in finally too, so a failing assertion cannot strand the import and hang the teardown.
        try {
            vm.viewModelScope.cancel()
            // Only importOpml's own cancellation cancels the import (it runs on the app scope); once that
            // has happened and a step of the import is held, the import is still running.
            awaitConditionBlocking { import.isCancelled && importDispatcher.queuedCount > 0 }
            assertEquals(OpmlOperation.Importing, vm.opmlRunning.value, "busy until the import has actually stopped")
            assertTrue(vm.opmlBusy.value)
            assertFalse(import.isCompleted, "the import is still running")

            importDispatcher.release()
            awaitConditionBlocking { vm.opmlRunning.value == null }

            assertTrue(import.isCompleted, "busy was released only after the import stopped")
            assertFalse(vm.opmlBusy.value)
            assertNull(vm.opmlResult.value, "a cancelled import records no result")
        } finally {
            importDispatcher.release()
        }
    }

    @Test
    fun importOpmlRunsTheFileReadOnTheInjectedDispatcher() = runTest {
        val xml = """<opml><body><outline text="Feed" xmlUrl="https://ex.com/feed"/></body></opml>"""
        val path = FileIO.join(dir, "import-dispatcher.opml")
        FileIO.writeText(path, xml)
        val counting = CountingDispatcher()
        val vm = fx.newViewModel(
            feedFetcher = fx.succeedingFetcher(),
            fileSelector = FakeFileSelector(openPath = path),
            dispatcher = counting,
        )

        vm.importOpml()

        awaitConditionBlocking { vm.opmlResult.value != null }
        assertIs<OpmlResult.Imported>(vm.opmlResult.value)
        assertTrue(counting.dispatchCount > 0)
    }

    @Test
    fun importAndExportOpmlRefuseToRunConcurrently() = runTest {
        val selector = SuspendingFileSelector()
        val vm = fx.newViewModel(fileSelector = selector)

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
        val vm = fx.newViewModel(feedFetcher = fx.succeedingFetcher())

        vm.importDocument(xml)

        awaitConditionBlocking { vm.opmlResult.value != null }
        assertEquals(OpmlResult.Imported(added = 1, failed = 0), vm.opmlResult.value)
        assertFalse(vm.opmlBusy.value)
    }

    @Test
    fun importDocumentRunsThroughTheSharedControllerAndRefusesASecondConcurrentRun() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = fx.newViewModel(feedFetcher = fx.gatedSucceedingFetcher(gate))

        vm.importDocument(ONE_FEED_OPML)
        assertEquals(OpmlOperation.Importing, vm.opmlRunning.value)
        assertTrue(vm.opmlBusy.value, "busy while the import runs")

        // Refused while the first one still runs: nothing changes, and only one import happens.
        vm.importDocument(TWO_FEED_OPML)
        assertEquals(OpmlOperation.Importing, vm.opmlRunning.value)

        gate.complete(Unit)
        awaitConditionBlocking { vm.opmlResult.value != null }
        assertEquals(OpmlResult.Imported(added = 1, failed = 0), vm.opmlResult.value)
        assertFalse(vm.opmlBusy.value)
        assertEquals(1, db.feedsQueries.getAllIncludingDeleted().executeAsList().size)
    }

    @Test
    fun importDocumentReportsFailedForAnUnreadableFile() = runTest {
        val vm = fx.newViewModel()

        vm.importDocument(null)

        assertEquals(OpmlResult.ImportFailed, vm.opmlResult.value)
        assertFalse(vm.opmlBusy.value)
    }

    @Test
    fun startingANewOperationClearsThePreviousResult() = runTest {
        val selector = SuspendingFileSelector()
        val vm = fx.newViewModel(fileSelector = selector)
        vm.importDocument(null)
        assertEquals(OpmlResult.ImportFailed, vm.opmlResult.value)

        vm.importOpml()

        assertNull(vm.opmlResult.value, "a stale result must not outlive the next operation's start")
        selector.openDeferred.complete(null)
    }

    @Test
    fun aPendingRequestIsHandedOutOnlyWhileNothingRuns() = runTest {
        val selector = SuspendingFileSelector()
        val vm = fx.newViewModel(fileSelector = selector)
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
        val vm = fx.newViewModel(fileSelector = selector)
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
        val vm = fx.newViewModel(fileSelector = selector)
        lastOpmlController.tryBegin(OpmlOperation.Exporting)
        lastOpmlController.request(OpmlRequest.ImportFile)

        runDesktopComposeUiTest {
            setContent { DataTabContent(vm) }
            waitForIdle()
        }

        assertEquals(0, selector.openCount)
        assertEquals(OpmlRequest.ImportFile, vm.pendingOpmlRequest.value)
    }

}
