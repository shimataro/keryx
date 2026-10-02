package works.merc.keryx.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.core.UpdateException
import works.merc.keryx.app.core.UpdateStage
import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.data.remote.UpdateDownloader
import works.merc.keryx.app.domain.InstallLaunchResult
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.UpdateAsset
import works.merc.keryx.app.domain.UpdateAssetKind
import works.merc.keryx.app.domain.UpdateChecker
import works.merc.keryx.app.domain.UpdateInstaller
import works.merc.keryx.app.domain.UpdatePlan
import works.merc.keryx.app.domain.UpdateRepository
import works.merc.keryx.app.domain.UpdateState
import works.merc.keryx.app.platform.InstallKind
import works.merc.keryx.app.platform.InstallLocation
import works.merc.keryx.app.ui.navigation.SettingsOpenRequest
import works.merc.keryx.app.ui.navigation.SettingsOpenRequests
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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

/** A read-only install location: the policy then plans [UpdatePlan.OpenReleasePage] (nothing to self-replace). */
private val READ_ONLY_MAC_LOCATION = WRITABLE_MAC_LOCATION.copy(parentWritable = false)

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }

private fun releaseJson(version: String, sizeBytes: Int, sha256: String) = """
    {"tag_name":"v$version","html_url":"https://ex.com/$version","prerelease":false,"draft":false,"assets":[
        {"name":"Keryx-$version-macos-arm64.zip","browser_download_url":"https://release-assets.githubusercontent.com/x.zip",
         "size":$sizeBytes,"digest":"sha256:$sha256","state":"uploaded"}
    ]}
""".trimIndent()

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
 * tray's and the Help menu's single update entry. Every state with something to do must land on
 * the Updates tab (via the [SettingsOpenRequests] router, raising the window through
 * [activationRequests]) and run exactly one of the two actions; the in-flight states must do
 * nothing at all. Which action a state maps to is `TrayActionPolicyTest`'s `updateMenuAction`
 * coverage; this suite checks the handler wires that decision to navigation and the action.
 *
 * Two layers. The lambda-based tests inject counting lambdas and pin the handler's own dispatch.
 * The "end to end" tests then click with a real [UpdateRepository] (MockEngine-backed
 * [UpdateChecker]/[UpdateDownloader], a [RecordingInstaller], a temp directory), wired the way
 * `main.kt` wires it — `checkForUpdate` runs the repository's [UpdateRepository.check] (what
 * `SettingsViewModel.checkForUpdate` ultimately calls) and `performPrimaryAction` is
 * `repo::performPrimaryAction` — so they prove the real wiring does the right thing: an installable
 * find downloads, a non-installable one re-checks without downloading, and a ready download is
 * handed to the installer. They use wall-clock polling since the repository runs on its own scope.
 */
class UpdateMenuActionTest {
    private var settingsOpenRequests = SettingsOpenRequests()
    private var checks = 0
    private var primaryActions = 0

    private val tempDirs = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        tempDirs.forEach { it.deleteRecursively() }
    }

    @BeforeTest
    fun setUp() {
        // activationRequests is a process-wide replay = 1 flow; start each test from an empty cache.
        activationRequests.resetReplayCache()
    }

    private fun click(state: UpdateState) =
        onUpdateMenuItemClicked(state, settingsOpenRequests, checkForUpdate = { checks++ }, performPrimaryAction = { primaryActions++ })

    private fun assertOpenedUpdatesTab() {
        assertEquals(SettingsOpenRequest("updates"), settingsOpenRequests.pending.value)
        assertEquals(listOf(Unit), activationRequests.replayCache, "the window must be brought to front")
    }

    private fun update(installable: Boolean): AvailableUpdate {
        val asset = UpdateAsset("Keryx-2.0.0-macos-arm64.zip", "https://x", 100L, "a".repeat(64), UpdateAssetKind.MAC_APP_ZIP)
        return if (installable) {
            AvailableUpdate("2.0.0", "https://ex.com/2.0.0", null, asset, UpdatePlan.SelfReplace(asset))
        } else {
            AvailableUpdate("2.0.0", "https://ex.com/2.0.0", null, null, UpdatePlan.OpenReleasePage)
        }
    }

    @Test
    fun idleAndUpToDateOpenTheUpdatesTabAndRunACheck() {
        listOf(UpdateState.Idle, UpdateState.UpToDate).forEach { state ->
            settingsOpenRequests = SettingsOpenRequests()
            activationRequests.resetReplayCache()
            checks = 0

            click(state)

            assertOpenedUpdatesTab()
            assertEquals(1, checks, state.toString())
        }
        assertEquals(0, primaryActions)
    }

    /** Nothing downloadable here: the tab links the release page, and the check refreshes it. */
    @Test
    fun aNonInstallableUpdateOpensTheUpdatesTabAndRunsACheckInsteadOfTheBrowser() {
        click(UpdateState.Available(update(installable = false)))

        assertOpenedUpdatesTab()
        assertEquals(1, checks)
        assertEquals(0, primaryActions)
    }

    @Test
    fun anInstallableUpdateAFailureAndAReadyDownloadOpenTheUpdatesTabAndRunThePrimaryAction() {
        listOf(
            UpdateState.Available(update(installable = true)),
            UpdateState.Failed(null, UpdateException(UpdateStage.CHECK, "no network")),
            UpdateState.Ready(update(installable = true), "/tmp/x.zip"),
        ).forEach { state ->
            settingsOpenRequests = SettingsOpenRequests()
            activationRequests.resetReplayCache()
            primaryActions = 0

            click(state)

            assertOpenedUpdatesTab()
            assertEquals(1, primaryActions, state.toString())
        }
        assertEquals(0, checks)
    }

    @Test
    fun theInFlightStatesDoNothingAtAll() {
        val update = update(installable = true)
        listOf(
            UpdateState.Checking,
            UpdateState.Downloading(update, 1, 2),
            UpdateState.Verifying(update),
            UpdateState.Installing(update),
        ).forEach { click(it) }

        assertNull(settingsOpenRequests.pending.value, "no navigation for an action already in flight")
        assertTrue(activationRequests.replayCache.isEmpty())
        assertEquals(0, checks)
        assertEquals(0, primaryActions)
    }

    // --- End to end: a real UpdateRepository behind the same wiring main.kt uses ---

    private class Fixture(
        val repo: UpdateRepository,
        val scope: CoroutineScope,
        val installer: RecordingInstaller,
        val checkRequests: AtomicInteger,
        val downloadRequests: AtomicInteger,
    )

    private fun fixture(canInstall: Boolean): Fixture {
        val payload = Random(7).nextBytes(4 * 1024)
        val checkRequests = AtomicInteger()
        val downloadRequests = AtomicInteger()
        val checkerClient = HttpClient(
            MockEngine {
                checkRequests.incrementAndGet()
                respond(releaseJson("2.0.0", payload.size, sha256Hex(payload)), HttpStatusCode.OK)
            },
        ) { expectSuccess = false }
        val downloaderClient = HttpClient(
            MockEngine {
                downloadRequests.incrementAndGet()
                respond(payload, HttpStatusCode.OK)
            },
        ) { expectSuccess = false }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes.add(it) }
        val installer = RecordingInstaller(canInstall)
        val location = if (canInstall) WRITABLE_MAC_LOCATION else READ_ONLY_MAC_LOCATION
        val dir = createTempDirectory("update-menu-action-test").toFile().also { tempDirs.add(it) }
        val repo = UpdateRepository(
            checker = UpdateChecker(checkerClient, currentVersion = "1.0.0", repoSlug = "owner/repo", location = location),
            downloader = UpdateDownloader(downloaderClient),
            installer = installer,
            notificationCenter = NotificationCenter(),
            scope = scope,
            location = location,
            cacheDirOverride = dir.path,
        )
        return Fixture(repo, scope, installer, checkRequests, downloadRequests)
    }

    private fun clickWithRealRepository(f: Fixture) =
        onUpdateMenuItemClicked(
            f.repo.state.value,
            settingsOpenRequests,
            checkForUpdate = { f.scope.launch { f.repo.check() } },
            performPrimaryAction = f.repo::performPrimaryAction,
        )

    @Test
    fun endToEndAnInstallableAvailableUpdateStartsItsDownload() {
        val f = fixture(canInstall = true)
        runBlocking { f.repo.check() }
        assertIs<UpdateState.Available>(f.repo.state.value)
        assertEquals(1, f.checkRequests.get())

        clickWithRealRepository(f)

        awaitConditionBlocking { f.repo.state.value is UpdateState.Ready }
        assertEquals(1, f.downloadRequests.get())
        assertEquals(1, f.checkRequests.get(), "starting a download is not a re-check")
        assertOpenedUpdatesTab()
    }

    @Test
    fun endToEndANonInstallableAvailableUpdateRechecksAndNeverDownloads() {
        val f = fixture(canInstall = false)
        runBlocking { f.repo.check() }
        val available = assertIs<UpdateState.Available>(f.repo.state.value)
        assertEquals(UpdatePlan.OpenReleasePage, available.update.plan)
        assertEquals(1, f.checkRequests.get())

        clickWithRealRepository(f)

        awaitConditionBlocking { f.checkRequests.get() == 2 }
        awaitConditionBlocking { f.repo.state.value is UpdateState.Available }
        assertEquals(0, f.downloadRequests.get(), "nothing is downloadable here")
        assertOpenedUpdatesTab()
    }

    @Test
    fun endToEndAReadyUpdateIsHandedToTheInstaller() {
        val f = fixture(canInstall = true)
        runBlocking { f.repo.check() }
        f.repo.startDownload()
        awaitConditionBlocking { f.repo.state.value is UpdateState.Ready }

        clickWithRealRepository(f)

        awaitConditionBlocking { f.installer.installedVersions.isNotEmpty() }
        assertEquals(listOf("2.0.0"), f.installer.installedVersions.toList())
        assertOpenedUpdatesTab()
    }
}
