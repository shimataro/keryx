package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.core.UpdateStage
import works.merc.keryx.app.data.remote.UpdateDownloader
import works.merc.keryx.app.platform.InstallKind
import works.merc.keryx.app.platform.InstallLocation
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val WRITABLE_MAC_LOCATION = InstallLocation(
    InstallKind.MAC_APP_BUNDLE, appRoot = "/Applications/Keryx.app", launcherPath = null, parentWritable = true, translocated = false,
)

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }

private fun releaseJson(version: String, assetName: String, assetUrl: String, sizeBytes: Int, sha256: String) = """
    {"tag_name":"v$version","html_url":"https://ex.com/$version","prerelease":false,"draft":false,"assets":[
        {"name":"$assetName","browser_download_url":"$assetUrl","size":$sizeBytes,"digest":"sha256:$sha256","state":"uploaded"}
    ]}
""".trimIndent()

private class RecordingNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String) = "feedGone:$feedTitle"
    override suspend fun feedUrlChanged(feedTitle: String) = "feedUrlChanged:$feedTitle"
    override suspend fun newArticles(count: Int) = "newArticles:$count"
    override suspend fun syncFailed(exception: KeryxException) = "syncFailed"
    override suspend fun opmlImported(added: Int, failed: Int) = "opmlImported:$added/$failed"
    override suspend fun updateAvailable(version: String) = "updateAvailable:$version"
    override suspend fun updateReadyToInstall(version: String) = "updateReadyToInstall:$version"
}

/**
 * Exercises [UpdateRepository] end to end against real (MockEngine-backed) [UpdateChecker]/
 * [UpdateDownloader] instances and a real temp directory — the only fakes are [UpdateInstaller]
 * (never actually installing anything) and the release/asset HTTP responses themselves. Uses
 * [runBlocking] with real wall-clock polling ([awaitState]) rather than `runTest`'s virtual
 * scheduler: [UpdateRepository]'s own [CoroutineScope] runs on a real dispatcher, the same reason
 * `SettingsViewModelTest`'s update-check tests do the same (see that file's own comment).
 */
class UpdateRepositoryTest {
    private val tempDirs = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun newTempDir(): String = createTempDirectory("update-repository-test").toFile().also { tempDirs.add(it) }.path

    private fun trackedScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes.add(it) }

    private fun awaitState(repo: UpdateRepository, timeoutMs: Long = 5_000, predicate: (UpdateState) -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate(repo.state.value)) {
            check(System.currentTimeMillis() < deadline) { "Timed out; last state was ${repo.state.value}" }
            delay(5)
        }
    }

    private fun checkerFor(releaseBody: () -> String): UpdateChecker {
        val client = HttpClient(MockEngine { respond(releaseBody(), HttpStatusCode.OK) }) { expectSuccess = false }
        return UpdateChecker(client, currentVersion = "1.0.0", repoSlug = "owner/repo", location = WRITABLE_MAC_LOCATION)
    }

    private fun noOpInstaller(canInstall: Boolean = true) = object : UpdateInstaller {
        override fun canInstall(plan: UpdatePlan) = canInstall
        override suspend fun install(filePath: String, update: AvailableUpdate) = InstallLaunchResult.Failed("not used")
    }

    @Test
    fun checkThenStartDownloadProducesAVerifiedReadyFile() {
        val payload = Random(1).nextBytes(64 * 1024)
        val sha256 = sha256Hex(payload)
        val downloaderClient = HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }
        val repo = UpdateRepository(
            checker = checkerFor { releaseJson("2.0.0", "Keryx-2.0.0-macos-arm64.zip", "https://release-assets.githubusercontent.com/x.zip", payload.size, sha256) },
            downloader = UpdateDownloader(downloaderClient),
            installer = noOpInstaller(),
            notificationCenter = NotificationCenter(),
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
        )

        runBlocking { repo.check() }
        assertIs<UpdateState.Available>(repo.state.value)

        repo.startDownload()
        awaitState(repo) { it is UpdateState.Ready }

        val ready = repo.state.value as UpdateState.Ready
        assertEquals("2.0.0", ready.update.version)
        assertContentEqualsFile(payload, ready.filePath)
        assertFalse(File("${ready.filePath}.part").exists())
    }

    @Test
    fun startDownloadCalledTwiceIssuesOnlyOneDownloadRequest() {
        val payload = Random(2).nextBytes(64 * 1024)
        val sha256 = sha256Hex(payload)
        var requestCount = 0
        val downloaderClient = HttpClient(MockEngine { requestCount++; respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }
        val repo = UpdateRepository(
            checker = checkerFor { releaseJson("2.0.0", "Keryx-2.0.0-macos-arm64.zip", "https://release-assets.githubusercontent.com/x.zip", payload.size, sha256) },
            downloader = UpdateDownloader(downloaderClient),
            installer = noOpInstaller(),
            notificationCenter = NotificationCenter(),
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
        )
        runBlocking { repo.check() }

        repo.startDownload()
        repo.startDownload() // must be a no-op: a download is already running/done

        awaitState(repo) { it is UpdateState.Ready }
        assertEquals(1, requestCount)
    }

    @Test
    fun cancelDownloadRevertsToAvailableAndRemovesThePartFile() {
        val payload = Random(3).nextBytes(64 * 1024)
        val sha256 = sha256Hex(payload)
        val gate = CompletableDeferred<Unit>()
        val downloaderClient = HttpClient(MockEngine { gate.await(); respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }
        val cacheDir = newTempDir()
        val repo = UpdateRepository(
            checker = checkerFor { releaseJson("2.0.0", "Keryx-2.0.0-macos-arm64.zip", "https://release-assets.githubusercontent.com/x.zip", payload.size, sha256) },
            downloader = UpdateDownloader(downloaderClient),
            installer = noOpInstaller(),
            notificationCenter = NotificationCenter(),
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = cacheDir,
        )
        runBlocking { repo.check() }

        repo.startDownload()
        awaitState(repo) { it is UpdateState.Downloading }
        repo.cancelDownload()

        awaitState(repo) { it is UpdateState.Available }
        val partFile = File(cacheDir, "updates/2.0.0/Keryx-2.0.0-macos-arm64.zip.part")
        assertFalse(partFile.exists())
    }

    @Test
    fun twoCollectorsObserveTheSameStateSequence() {
        val payload = Random(4).nextBytes(1024)
        val sha256 = sha256Hex(payload)
        val downloaderClient = HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }
        val repo = UpdateRepository(
            checker = checkerFor { releaseJson("2.0.0", "Keryx-2.0.0-macos-arm64.zip", "https://release-assets.githubusercontent.com/x.zip", payload.size, sha256) },
            downloader = UpdateDownloader(downloaderClient),
            installer = noOpInstaller(),
            notificationCenter = NotificationCenter(),
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
        )
        val seenByFirst = mutableListOf<UpdateState>()
        val seenBySecond = mutableListOf<UpdateState>()
        val collectorScope = trackedScope()
        collectorScope.launch { repo.state.collect { seenByFirst.add(it) } }
        collectorScope.launch { repo.state.collect { seenBySecond.add(it) } }

        runBlocking { repo.check() }
        repo.startDownload()
        awaitState(repo) { it is UpdateState.Ready }

        assertTrue(seenByFirst.isNotEmpty())
        assertEquals(seenByFirst, seenBySecond)
        assertEquals(repo.state.value, seenByFirst.last())
    }

    @Test
    fun sweepPreservesTheCurrentlyReadyVersionsDirectory() {
        val payload = Random(5).nextBytes(1024)
        val sha256 = sha256Hex(payload)
        val downloaderClient = HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }
        val cacheDir = newTempDir()
        val repo = UpdateRepository(
            checker = checkerFor { releaseJson("2.0.0", "Keryx-2.0.0-macos-arm64.zip", "https://release-assets.githubusercontent.com/x.zip", payload.size, sha256) },
            downloader = UpdateDownloader(downloaderClient),
            installer = noOpInstaller(),
            notificationCenter = NotificationCenter(),
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = cacheDir,
        )
        runBlocking { repo.check() }
        repo.startDownload()
        awaitState(repo) { it is UpdateState.Ready }
        val versionDir = File(cacheDir, "updates/2.0.0")
        assertTrue(versionDir.exists())

        // A second check finding the *same* version again must not sweep away the Ready artifact
        // it's still pointing at.
        runBlocking { repo.check() }

        assertTrue(versionDir.exists())
        assertIs<UpdateState.Ready>(repo.state.value)
    }

    @Test
    fun sweepDeletesUnrelatedStaleDirectories() {
        val cacheDir = newTempDir()
        File(cacheDir, "updates/9.9.9").apply { mkdirs() }
        File(cacheDir, "updates/9.9.9/stale.zip").writeText("stale")
        val repo = UpdateRepository(
            checker = checkerFor { """{"tag_name":"v1.0.0","html_url":"https://ex.com/1.0.0","prerelease":false,"draft":false}""" },
            downloader = UpdateDownloader(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) { expectSuccess = false }),
            installer = noOpInstaller(),
            notificationCenter = NotificationCenter(),
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = cacheDir,
        )

        runBlocking { repo.check() } // current version == latest → UpToDate, nothing in use

        assertFalse(File(cacheDir, "updates/9.9.9").exists())
    }

    @Test
    fun aNewerVersionReplacesReadyAndDeletesItsOldDirectory() {
        val payload = Random(6).nextBytes(1024)
        val sha256 = sha256Hex(payload)
        val downloaderClient = HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }
        val cacheDir = newTempDir()
        var version = "2.0.0"
        val repo = UpdateRepository(
            checker = checkerFor { releaseJson(version, "Keryx-$version-macos-arm64.zip", "https://release-assets.githubusercontent.com/$version.zip", payload.size, sha256) },
            downloader = UpdateDownloader(downloaderClient),
            installer = noOpInstaller(),
            notificationCenter = NotificationCenter(),
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = cacheDir,
        )
        runBlocking { repo.check() }
        repo.startDownload()
        awaitState(repo) { it is UpdateState.Ready }
        val oldVersionDir = File(cacheDir, "updates/2.0.0")
        assertTrue(oldVersionDir.exists())

        version = "3.0.0"
        runBlocking { repo.check() }

        assertIs<UpdateState.Available>(repo.state.value)
        assertEquals("3.0.0", (repo.state.value as UpdateState.Available).update.version)
        assertFalse(oldVersionDir.exists())
    }

    // --- install() and the app-exit signal ---

    /** Builds a repository already sitting at [UpdateState.Ready], the only state [install] acts on. */
    private fun readyRepo(installer: UpdateInstaller, seed: Int): UpdateRepository {
        val payload = Random(seed).nextBytes(64 * 1024)
        val downloaderClient = HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }
        val repo = UpdateRepository(
            checker = checkerFor {
                releaseJson(
                    "2.0.0", "Keryx-2.0.0-macos-arm64.zip",
                    "https://release-assets.githubusercontent.com/x.zip", payload.size, sha256Hex(payload),
                )
            },
            downloader = UpdateDownloader(downloaderClient),
            installer = installer,
            notificationCenter = NotificationCenter(),
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
        )
        runBlocking { repo.check() }
        repo.startDownload()
        awaitState(repo) { it is UpdateState.Ready }
        return repo
    }

    /**
     * Subscribes to [UpdateRepository.installLaunched] and hands back a deferred completing on its
     * first emission. Returns only once the subscription is actually registered ([onSubscription]),
     * since a `replay = 0` flow drops anything emitted before that — without this the test could
     * "pass" by missing a signal it should have caught.
     */
    private fun exitSignalOf(repo: UpdateRepository): CompletableDeferred<Unit> {
        val subscribed = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        trackedScope().launch {
            repo.installLaunched.onSubscription { subscribed.complete(Unit) }.collect { exited.complete(Unit) }
        }
        runBlocking { withTimeout(5_000) { subscribed.await() } }
        return exited
    }

    /**
     * The regression guard for "clicking install just quit the app": [UpdateState.Installing] is
     * set the moment an install starts, while the installer is still extracting and staging, so
     * exiting on *that* killed the process before the self-replace script was ever launched. Only
     * an [InstallLaunchResult.Launched] result may signal the app to exit.
     */
    @Test
    fun installSignalsTheAppToExitOnlyAfterTheInstallerHasLaunchedSomething() {
        val gate = CompletableDeferred<Unit>()
        val installer = GatedInstaller(InstallLaunchResult.Launched, gate)
        val repo = readyRepo(installer, seed = 7)
        val exited = exitSignalOf(repo)

        repo.install()
        runBlocking { withTimeout(5_000) { installer.started.await() } }
        awaitState(repo) { it is UpdateState.Installing }

        assertFalse(exited.isCompleted, "the app was told to exit while the installer was still working")

        gate.complete(Unit)
        runBlocking { withTimeout(5_000) { exited.await() } }
        assertIs<UpdateState.Installing>(repo.state.value)
    }

    @Test
    fun aFailedInstallNeverSignalsTheAppToExit() {
        val installer = GatedInstaller(InstallLaunchResult.Failed("no installer here"))
        val repo = readyRepo(installer, seed = 8)
        val exited = exitSignalOf(repo)

        repo.install()
        awaitState(repo) { it is UpdateState.Failed }

        assertEquals(UpdateStage.INSTALL, (repo.state.value as UpdateState.Failed).exception.stage)
        assertFalse(exited.isCompleted)
    }

    @Test
    fun awaitingUserConsentReturnsToReadyWithoutSignallingTheAppToExit() {
        val installer = GatedInstaller(InstallLaunchResult.AwaitingUserConsent)
        val repo = readyRepo(installer, seed = 9)
        val exited = exitSignalOf(repo)

        repo.install()
        runBlocking { withTimeout(5_000) { installer.started.await() } }
        awaitState(repo) { it is UpdateState.Ready }

        assertFalse(exited.isCompleted)
    }

    /**
     * The regression guard for the real bug this class exists to catch: `installer.install()`
     * throwing (as `RealProcessLauncher.launch()` did unconditionally before its
     * `redirectInput(Redirect.DISCARD)` bug was fixed — see `DetachedProcess.kt`'s own KDoc) must
     * turn into `Failed`, not leave `state` stuck at `Installing` forever with no error and no exit
     * signal.
     */
    @Test
    fun anInstallerThatThrowsFailsInsteadOfFreezing() {
        val installer = ThrowingInstaller()
        val repo = readyRepo(installer, seed = 10)
        val exited = exitSignalOf(repo)

        repo.install()
        awaitState(repo) { it is UpdateState.Failed }

        assertEquals(UpdateStage.INSTALL, (repo.state.value as UpdateState.Failed).exception.stage)
        assertFalse(exited.isCompleted)
    }
}

/** A fake [UpdateInstaller] whose [install] throws, as [GatedInstaller] cannot express. */
private class ThrowingInstaller : UpdateInstaller {
    override fun canInstall(plan: UpdatePlan) = true

    override suspend fun install(filePath: String, update: AvailableUpdate): InstallLaunchResult =
        throw IllegalArgumentException("simulated launcher failure")
}

/**
 * A fake [UpdateInstaller] returning [result], optionally not until [gate] completes — which is
 * what lets a test inspect the repository *while* an install is still in flight, the window the
 * app used to quit itself in.
 */
private class GatedInstaller(
    private val result: InstallLaunchResult,
    private val gate: CompletableDeferred<Unit>? = null,
) : UpdateInstaller {
    /** Completes as soon as [install] is entered, so a test never has to guess when that happened. */
    val started = CompletableDeferred<Unit>()

    override fun canInstall(plan: UpdatePlan) = true

    override suspend fun install(filePath: String, update: AvailableUpdate): InstallLaunchResult {
        started.complete(Unit)
        gate?.await()
        return result
    }
}

private fun assertContentEqualsFile(expected: ByteArray, path: String) {
    assertEquals(expected.toList(), File(path).readBytes().toList())
}
