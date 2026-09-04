package works.merc.keryx.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.data.remote.UpdateDownloader
import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.domain.InstallLaunchResult
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.UpdateChecker
import works.merc.keryx.app.domain.UpdateInstaller
import works.merc.keryx.app.domain.UpdatePlan
import works.merc.keryx.app.domain.UpdateRepository
import works.merc.keryx.app.domain.UpdateState
import works.merc.keryx.app.platform.InstallKind
import works.merc.keryx.app.platform.InstallLocation
import works.merc.keryx.app.ui.home.NotificationCenterViewModel
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val WRITABLE_MAC_LOCATION = InstallLocation(
    InstallKind.MAC_APP_BUNDLE, appRoot = "/Applications/Keryx.app", launcherPath = null, parentWritable = true, translocated = false,
)

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }

private fun releaseJson(version: String, sizeBytes: Int, sha256: String) = """
    {"tag_name":"v$version","html_url":"https://ex.com/$version","prerelease":false,"draft":false,"assets":[
        {"name":"Keryx-$version-macos-arm64.zip","browser_download_url":"https://release-assets.githubusercontent.com/x.zip",
         "size":$sizeBytes,"digest":"sha256:$sha256","state":"uploaded"}
    ]}
""".trimIndent()

/** A release whose tag matches the running version, so the checker reports "up to date". */
private val UP_TO_DATE_RELEASE_JSON = """
    {"tag_name":"v1.0.0","html_url":"https://ex.com/1.0.0","prerelease":false,"draft":false,"assets":[]}
""".trimIndent()

private class FakeNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String) = "feedGone:$feedTitle"
    override suspend fun feedUrlChanged(feedTitle: String) = "feedUrlChanged:$feedTitle"
    override suspend fun newArticles(count: Int) = "newArticles:$count"
    override suspend fun syncFailed(exception: KeryxException) = "syncFailed"
    override suspend fun opmlImported(added: Int, failed: Int) = "opmlImported:$added/$failed"
    override suspend fun updateAvailable(version: String) = "updateAvailable:$version"
    override suspend fun updateReadyToInstall(version: String) = "updateReadyToInstall:$version"
    override suspend fun tokenStorageFallback() = "tokenStorageFallback"
    override suspend fun tokenStorageFallbackDetail() = "tokenStorageFallbackDetail"
    override suspend fun tokenStorageNotPersisted() = "tokenStorageNotPersisted"
    override suspend fun tokenStorageNotPersistedDetail() = "tokenStorageNotPersistedDetail"
}

/** Records what [UpdateRepository.install] handed it, without ever launching anything. */
private class RecordingInstaller(private val canInstall: Boolean) : UpdateInstaller {
    val installedVersions = CopyOnWriteArrayList<String>()

    override fun canInstall(plan: UpdatePlan) = canInstall

    override suspend fun install(filePath: String, update: AvailableUpdate): InstallLaunchResult {
        installedVersions.add(update.version)
        return InstallLaunchResult.Failed("not launched in tests")
    }
}

/**
 * Covers `main.kt`'s [onUpdateMenuItemClicked] — the one click handler behind both the system
 * tray's and the Help menu's single update entry.
 *
 * Built on the same fixture shape as `UpdateRepositoryTest`: a real [UpdateRepository] over a
 * MockEngine-backed [UpdateChecker]/[UpdateDownloader] and a real temp directory, so each branch is
 * exercised against the actual state machine rather than a stub of it, and — like that suite — with
 * [runBlocking] plus wall-clock polling rather than `runTest`, since the repository's own scope runs
 * on a real dispatcher. The release-page hand-off goes through the function's own `openUrl` seam
 * (`BrowserOpener` is an `actual object` with no seam of its own — `NotificationRowActionTest`
 * documents the same constraint).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateMenuActionTest {
    private val tempDirs = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val openedUrls = CopyOnWriteArrayList<String>()

    @BeforeTest
    fun setUp() {
        // NotificationCenterViewModel's `alertToSurface` starts an eager `stateIn(viewModelScope)`
        // in its constructor, so Main must be installed before one is built.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        tempDirs.forEach { it.deleteRecursively() }
        Dispatchers.resetMain()
    }

    private fun newTempDir(): String = createTempDirectory("update-menu-action-test").toFile().also { tempDirs.add(it) }.path

    private fun trackedScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes.add(it) }

    private fun await(timeoutMs: Long = 5_000, describe: () -> String, condition: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline, describe)
            delay(5)
        }
    }

    private fun awaitState(repo: UpdateRepository, predicate: (UpdateState) -> Boolean) =
        await(describe = { "Timed out; last state was ${repo.state.value}" }) { predicate(repo.state.value) }

    /** Gives the launched work a moment to *not* do anything, for the no-op assertions. */
    private fun settle() = runBlocking { delay(200) }

    private class Fixture(
        val repo: UpdateRepository,
        val viewModel: NotificationCenterViewModel,
        val installer: RecordingInstaller,
        val downloadRequestCount: () -> Int,
    )

    /**
     * @param releaseBody the GitHub "latest release" JSON the checker sees.
     * @param canInstall what the fake [UpdateInstaller] reports, i.e. whether the found update
     *   comes back [AvailableUpdate.installable].
     * @param downloadSucceeds `false` makes the asset request fail, so a started download ends in
     *   [UpdateState.Failed].
     */
    private fun fixture(
        releaseBody: String,
        payload: ByteArray = ByteArray(0),
        canInstall: Boolean = true,
        downloadSucceeds: Boolean = true,
    ): Fixture {
        var requestCount = 0
        val checkerClient = HttpClient(MockEngine { respond(releaseBody, HttpStatusCode.OK) }) { expectSuccess = false }
        val downloaderClient = HttpClient(
            MockEngine {
                requestCount++
                if (downloadSucceeds) respond(payload, HttpStatusCode.OK) else respond("", HttpStatusCode.InternalServerError)
            },
        ) { expectSuccess = false }
        val notificationCenter = NotificationCenter()
        val installer = RecordingInstaller(canInstall)
        val repo = UpdateRepository(
            checker = UpdateChecker(checkerClient, currentVersion = "1.0.0", repoSlug = "owner/repo", location = WRITABLE_MAC_LOCATION),
            downloader = UpdateDownloader(downloaderClient),
            installer = installer,
            notificationCenter = notificationCenter,
            notificationMessages = FakeNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
        )
        return Fixture(repo, NotificationCenterViewModel(notificationCenter), installer) { requestCount }
    }

    /** A fixture whose check finds version 2.0.0, with a downloadable, digest-matching asset. */
    private fun availableFixture(canInstall: Boolean = true, downloadSucceeds: Boolean = true): Fixture {
        val payload = Random(7).nextBytes(4 * 1024)
        return fixture(
            releaseBody = releaseJson("2.0.0", payload.size, sha256Hex(payload)),
            payload = payload,
            canInstall = canInstall,
            downloadSucceeds = downloadSucceeds,
        )
    }

    private fun click(f: Fixture, state: UpdateState = f.repo.state.value) =
        onUpdateMenuItemClicked(state, trackedScope(), f.repo, f.viewModel, openedUrls::add)

    // --- Available ---

    @Test
    fun anInstallableAvailableUpdateStartsItsDownload() {
        val f = availableFixture()
        runBlocking { f.repo.check() }
        assertIs<UpdateState.Available>(f.repo.state.value)

        click(f)

        awaitState(f.repo) { it is UpdateState.Ready }
        assertEquals(1, f.downloadRequestCount())
        assertTrue(openedUrls.isEmpty(), "an installable update must never open the release page")
        // Starting the download closes the tray/menu with no other feedback, so this also opens the
        // Updates tab — see main.kt's startAndShowUpdatesTab.
        assertEquals(AppNotificationAction.ShowSettingsTab("updates"), f.viewModel.pendingAction?.action)
    }

    @Test
    fun aNonInstallableAvailableUpdateOpensTheReleasePageInsteadOfDownloading() {
        val f = availableFixture(canInstall = false)
        runBlocking { f.repo.check() }
        val available = assertIs<UpdateState.Available>(f.repo.state.value)

        click(f)
        settle()

        assertEquals(listOf(available.update.releaseUrl), openedUrls.toList())
        assertEquals(0, f.downloadRequestCount(), "nothing is downloadable here")
        assertIs<UpdateState.Available>(f.repo.state.value)
        assertNull(f.viewModel.pendingAction, "the release page is the entire hand-off here")
    }

    // --- Ready / Failed ---

    @Test
    fun aReadyUpdateIsHandedToTheInstaller() {
        val f = availableFixture()
        runBlocking { f.repo.check() }
        f.repo.startDownload()
        awaitState(f.repo) { it is UpdateState.Ready }

        click(f)

        await(describe = { "the installer was never invoked" }) { f.installer.installedVersions.isNotEmpty() }
        assertEquals(listOf("2.0.0"), f.installer.installedVersions.toList())
        // Install is followed shortly by the app restarting, so there's nothing worth opening the
        // Updates tab for here — unlike Available/Failed.
        assertNull(f.viewModel.pendingAction)
    }

    @Test
    fun aFailedUpdateRetriesTheDownload() {
        val f = availableFixture(downloadSucceeds = false)
        runBlocking { f.repo.check() }
        f.repo.startDownload()
        awaitState(f.repo) { it is UpdateState.Failed }
        assertEquals(1, f.downloadRequestCount())

        click(f)

        await(describe = { "the retry never issued a second request" }) { f.downloadRequestCount() == 2 }
        assertEquals(AppNotificationAction.ShowSettingsTab("updates"), f.viewModel.pendingAction?.action)
    }

    // --- states with an action already in flight ---

    @Test
    fun theInFlightStatesDoNothingAtAll() {
        val f = availableFixture()
        runBlocking { f.repo.check() }
        val update = assertIs<UpdateState.Available>(f.repo.state.value).update

        listOf(
            UpdateState.Checking,
            UpdateState.Downloading(update, 1, 2),
            UpdateState.Verifying(update),
            UpdateState.Installing(update),
        ).forEach { click(f, it) }
        settle()

        assertEquals(0, f.downloadRequestCount())
        assertTrue(openedUrls.isEmpty())
        assertTrue(f.installer.installedVersions.isEmpty())
    }

    // --- Idle / UpToDate: the check path ---

    @Test
    fun idleRunsACheckAndSurfacesAnInstallableFindOnTheUpdatesTab() {
        val f = availableFixture()

        click(f, UpdateState.Idle)

        awaitState(f.repo) { it is UpdateState.Available }
        await(describe = { "the updates tab was never requested" }) { f.viewModel.pendingAction != null }
        assertEquals(AppNotificationAction.ShowSettingsTab("updates"), f.viewModel.pendingAction?.action)
    }

    @Test
    fun upToDateRunsAnotherCheckAndLeavesTheSettingsDialogClosedWhenNothingIsFound() {
        val f = fixture(releaseBody = UP_TO_DATE_RELEASE_JSON)
        runBlocking { f.repo.check() }
        assertEquals(UpdateState.UpToDate, f.repo.state.value)

        click(f, UpdateState.UpToDate)
        settle()

        assertEquals(UpdateState.UpToDate, f.repo.state.value)
        assertNull(f.viewModel.pendingAction)
    }

    /**
     * A non-installable find has no in-app action on the Updates tab — the entry itself opens the
     * release page — so the check must not pull the settings dialog open for one.
     */
    @Test
    fun aCheckThatFindsANonInstallableUpdateDoesNotOpenTheSettingsDialog() {
        val f = availableFixture(canInstall = false)

        click(f, UpdateState.Idle)

        awaitState(f.repo) { it is UpdateState.Available }
        settle()
        assertNull(f.viewModel.pendingAction)
    }
}
