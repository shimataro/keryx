package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
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
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.core.UPDATE_RELEASE_WATCH_MAX_ATTEMPTS
import works.merc.keryx.app.core.UpdateStage
import works.merc.keryx.app.data.remote.UpdateDownloader
import works.merc.keryx.app.platform.InstallKind
import works.merc.keryx.app.platform.InstallLocation
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

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

/**
 * Same shape as [releaseJson], but with an empty `assets[]` — the state the release workflow
 * leaves a GitHub release in for the few minutes between publishing it and finishing the upload of
 * every platform's package. See `UpdateInstallPolicy.kt`'s `awaitsReleaseAsset` KDoc.
 */
private fun releaseJsonNoAssets(version: String) = """
    {"tag_name":"v$version","html_url":"https://ex.com/$version","prerelease":false,"draft":false,"assets":[]}
""".trimIndent()

/**
 * Asserts every element of [actual] appears in [expected], in that order — i.e. [actual] is what is
 * left of [expected] after zero or more elements are dropped. Used to check a conflating
 * [kotlinx.coroutines.flow.StateFlow] collector against the progression the emitter really produced:
 * missing values are fine (that is what conflation does), but an unknown value, a duplicate, or a
 * reordering is not. [label] names the collector in the failure message.
 */
private fun <T> assertSubsequenceOf(expected: List<T>, actual: List<T>, label: String) {
    var next = 0
    for (value in actual) {
        while (next < expected.size && expected[next] != value) next++
        if (next >= expected.size) {
            fail("$label observed $value, which the progression $expected never emits at that point (observed: $actual)")
        }
        next++
    }
}

private class RecordingNotificationMessages : NotificationMessages {
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

    /** Polls [condition] on the real wall clock until it holds, failing with [describe]'s message on
     * timeout — see this class's own KDoc for why these tests poll rather than use `runTest`. */
    private fun await(timeoutMs: Long = 5_000, describe: () -> String, condition: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline, describe)
            delay(5)
        }
    }

    private fun awaitState(repo: UpdateRepository, timeoutMs: Long = 5_000, predicate: (UpdateState) -> Boolean) =
        await(timeoutMs, { "Timed out; last state was ${repo.state.value}" }) { predicate(repo.state.value) }

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

    /**
     * Regression guard for this class's own KDoc promise: "an update is available" and "ready to
     * install" must read as one evolving notification-center row, not two left to accumulate —
     * see [UpdateRepository.postNotification]'s own KDoc.
     */
    @Test
    fun readyToInstallReplacesTheAvailableNotificationRatherThanAddingASecondOne() {
        val payload = Random(35).nextBytes(1024)
        val sha256 = sha256Hex(payload)
        val downloaderClient = HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }
        val notificationCenter = NotificationCenter()
        val repo = UpdateRepository(
            checker = checkerFor { releaseJson("2.0.0", "Keryx-2.0.0-macos-arm64.zip", "https://release-assets.githubusercontent.com/x.zip", payload.size, sha256) },
            downloader = UpdateDownloader(downloaderClient),
            installer = noOpInstaller(),
            notificationCenter = notificationCenter,
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
        )

        runBlocking { repo.check() }
        assertEquals(listOf("updateAvailable:2.0.0"), notificationCenter.items.value.map { it.message })

        repo.startDownload()
        awaitState(repo) { it is UpdateState.Ready }

        val items = notificationCenter.items.value
        assertEquals(1, items.size, "the \"available\" row must be replaced, not left alongside a new one")
        assertEquals("updateReadyToInstall:2.0.0", items.single().message)
        assertEquals(AppNotificationAction.ShowSettingsTab("updates"), items.single().action)
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

    // --- startDownload() as the Failed retry entry point ---

    @Test
    fun retryingADownloadStageFailureReDownloads() {
        val payload = Random(20).nextBytes(64 * 1024)
        val sha256 = sha256Hex(payload)
        var requestCount = 0
        val downloaderClient = HttpClient(
            MockEngine {
                requestCount++
                if (requestCount == 1) respond("", HttpStatusCode.InternalServerError) else respond(payload, HttpStatusCode.OK)
            },
        ) { expectSuccess = false }
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
        awaitState(repo) { it is UpdateState.Failed }
        assertEquals(UpdateStage.DOWNLOAD, (repo.state.value as UpdateState.Failed).exception.stage)

        repo.startDownload() // retry
        awaitState(repo) { it is UpdateState.Ready }
        assertEquals(2, requestCount)
    }

    @Test
    fun retryingAVerifyStageFailureReDownloads() {
        val payload = Random(21).nextBytes(64 * 1024)
        val corrupted = Random(22).nextBytes(64 * 1024)
        val sha256 = sha256Hex(payload)
        var requestCount = 0
        val downloaderClient = HttpClient(
            MockEngine {
                requestCount++
                respond(if (requestCount == 1) corrupted else payload, HttpStatusCode.OK)
            },
        ) { expectSuccess = false }
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
        awaitState(repo) { it is UpdateState.Failed }
        assertEquals(UpdateStage.VERIFY, (repo.state.value as UpdateState.Failed).exception.stage)

        repo.startDownload() // retry
        awaitState(repo) { it is UpdateState.Ready }
        assertEquals(2, requestCount)
        assertContentEqualsFile(payload, (repo.state.value as UpdateState.Ready).filePath)
    }

    @Test
    fun retryingAnInstallStageFailureReinstallsWithoutRedownloading() {
        var downloadRequestCount = 0
        val payload = Random(23).nextBytes(64 * 1024)
        val downloaderClient = HttpClient(MockEngine { downloadRequestCount++; respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }
        var installCount = 0
        val installer = object : UpdateInstaller {
            override fun canInstall(plan: UpdatePlan) = true
            override suspend fun install(filePath: String, update: AvailableUpdate): InstallLaunchResult {
                installCount++
                return if (installCount == 1) InstallLaunchResult.Failed("simulated") else InstallLaunchResult.Launched
            }
        }
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
        val filePath = (repo.state.value as UpdateState.Ready).filePath

        repo.install()
        awaitState(repo) { it is UpdateState.Failed }
        assertEquals(UpdateStage.INSTALL, (repo.state.value as UpdateState.Failed).exception.stage)
        val requestsBeforeRetry = downloadRequestCount

        repo.startDownload() // retry: must re-install the file already on disk, not re-download it
        awaitState(repo) { it is UpdateState.Installing }
        assertEquals(2, installCount)
        assertEquals(requestsBeforeRetry, downloadRequestCount)
        assertTrue(File(filePath).exists(), "the verified file must still be there for a direct re-install")
    }

    @Test
    fun aCheckCompletingDuringAnInstallKeepsTheDownloadThatInstallIsUsing() {
        val payload = Random(31).nextBytes(64 * 1024)
        val downloaderClient = HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }
        val body = releaseJson(
            "2.0.0", "Keryx-2.0.0-macos-arm64.zip",
            "https://release-assets.githubusercontent.com/x.zip", payload.size, sha256Hex(payload),
        )
        // The second check parks inside the network call, which is the window a user can click
        // Install through: check() leaves Ready untouched and disables nothing in the Updates tab.
        val checkGate = CompletableDeferred<Unit>()
        var checkCount = 0
        val checkerClient = HttpClient(
            MockEngine {
                checkCount++
                if (checkCount >= 2) checkGate.await()
                respond(body, HttpStatusCode.OK)
            },
        ) { expectSuccess = false }
        val installGate = CompletableDeferred<Unit>()
        val installer = GatedInstaller(InstallLaunchResult.Launched, installGate)
        val repo = UpdateRepository(
            checker = UpdateChecker(checkerClient, currentVersion = "1.0.0", repoSlug = "owner/repo", location = WRITABLE_MAC_LOCATION),
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
        val filePath = (repo.state.value as UpdateState.Ready).filePath

        trackedScope().launch { repo.check() } // snapshots `before = Ready`, then parks
        await(describe = { "the second check never reached the network" }) { checkCount >= 2 }
        repo.install()
        runBlocking { withTimeout(5_000) { installer.started.await() } }
        checkGate.complete(Unit) // the check now folds in on top of Installing

        // nextStateAfterCheck passes Installing straight through, so `after` is not Ready — which a
        // `after !is Ready` supersede-check would read as "this version was replaced" and delete out
        // from under the running extraction.
        await(describe = { "the check never finished" }) { checkCount >= 2 && repo.state.value is UpdateState.Installing }
        Thread.sleep(200)
        assertTrue(File(filePath).exists(), "a check must not delete the download the running install is reading")
        installGate.complete(Unit)
    }

    @Test
    fun retryingAnInstallStageFailureTwiceStartsOnlyOneInstall() {
        val payload = Random(29).nextBytes(64 * 1024)
        val downloaderClient = HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }
        var installCount = 0
        val gate = CompletableDeferred<Unit>()
        val secondInstallEntered = CompletableDeferred<Unit>()
        val installer = object : UpdateInstaller {
            override fun canInstall(plan: UpdatePlan) = true
            override suspend fun install(filePath: String, update: AvailableUpdate): InstallLaunchResult {
                installCount++
                return when (installCount) {
                    1 -> InstallLaunchResult.Failed("simulated")
                    // Hold the retry inside install() so a *second* retry has a real window to slip
                    // through — the exact race two Retry surfaces on one Failed state can produce.
                    2 -> { gate.await(); InstallLaunchResult.Launched }
                    else -> { secondInstallEntered.complete(Unit); InstallLaunchResult.Launched }
                }
            }
        }
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
        repo.install()
        awaitState(repo) { it is UpdateState.Failed }

        repo.startDownload() // first retry — parks inside install()
        awaitState(repo) { it is UpdateState.Installing }
        repo.startDownload() // second retry — must find Installing, not Failed, and do nothing

        Thread.sleep(200) // give a leaked third install every chance to appear
        assertEquals(2, installCount, "a retry must never start a second install over one already running")
        assertTrue(!secondInstallEntered.isCompleted)
        gate.complete(Unit)
    }

    @Test
    fun retryingAnInstallStageFailureRedownloadsWhenTheVerifiedFileIsGone() {
        var downloadRequestCount = 0
        val payload = Random(24).nextBytes(64 * 1024)
        val downloaderClient = HttpClient(MockEngine { downloadRequestCount++; respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }
        val installer = object : UpdateInstaller {
            override fun canInstall(plan: UpdatePlan) = true
            override suspend fun install(filePath: String, update: AvailableUpdate) = InstallLaunchResult.Failed("simulated")
        }
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
        val filePath = (repo.state.value as UpdateState.Ready).filePath

        repo.install()
        awaitState(repo) { it is UpdateState.Failed }
        assertTrue(File(filePath).delete(), "test setup: could not remove the verified file")
        val requestsBeforeRetry = downloadRequestCount

        repo.startDownload() // retry: the file is gone, so this must fall back to a fresh download
        awaitState(repo) { it is UpdateState.Ready }
        assertTrue(downloadRequestCount > requestsBeforeRetry, "a new download request must have been issued")
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

    /**
     * Every surface that reads [UpdateRepository.state] — tray, notification center, Updates tab —
     * must be observing the *one* shared state machine, not a per-collector copy of it: each sees
     * only states that machine really emitted, in the order it emitted them, and all of them
     * converge on the same terminal value.
     *
     * Deliberately does **not** assert the two recorded sequences are identical. A `StateFlow` is
     * conflating: it only guarantees the latest value arrives, and intermediate values are dropped
     * per collector, *independently*. `Available -> Downloading -> Verifying -> Ready` are all
     * written back-to-back by `UpdateRepository.runDownload` on [Dispatchers.Default], so on a
     * slow/low-core machine (observed: the 2-core `windows-latest` CI runner) one collector can
     * conflate a value the other keeps — an `assertEquals` of the two lists was flaky for exactly
     * that reason, not because anything was wrong with the state machine.
     *
     * What it asserts instead is that each sequence is a *subsequence* of the single canonical
     * progression this run emits, payloads included. That is not weaker than the old comparison: a
     * wrong version, a wrong progress reading, a wrong `filePath`, an out-of-order transition, or a
     * state the machine never produces all still fail — only conflation is tolerated.
     */
    @Test
    fun everyCollectorObservesTheSameSharedStateProgression() {
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
        // Appended from the collectors' own Dispatchers.Default threads and read from this one, with
        // no happens-before edge between them — a plain ArrayList here would make even the observed
        // contents undefined, quite apart from the conflation this test's KDoc describes.
        val seenByFirst = CopyOnWriteArrayList<UpdateState>()
        val seenBySecond = CopyOnWriteArrayList<UpdateState>()
        val collectorScope = trackedScope()
        // Both collectors must actually be subscribed — not just launch()ed — before check() starts
        // mutating state, so each is guaranteed to observe the progression from somewhere at or
        // before its first transition. (This does not guarantee either one *sees* the leading Idle:
        // onSubscription only fires once the subscription is registered, and the collector reads the
        // state slot after that — by which point check() may already have moved it on. Hence the
        // subsequence assertion below rather than one anchored on Idle.)
        val firstSubscribed = CompletableDeferred<Unit>()
        val secondSubscribed = CompletableDeferred<Unit>()
        collectorScope.launch {
            repo.state.onSubscription { firstSubscribed.complete(Unit) }.collect { seenByFirst.add(it) }
        }
        collectorScope.launch {
            repo.state.onSubscription { secondSubscribed.complete(Unit) }.collect { seenBySecond.add(it) }
        }
        runBlocking { withTimeout(5_000) { firstSubscribed.await(); secondSubscribed.await() } }

        runBlocking { repo.check() }
        repo.startDownload()
        awaitState(repo) { it is UpdateState.Ready }
        // repo.state.value reaching Ready says nothing about the collectors having been handed it
        // yet — they run on their own threads. Wait for them, not just for the state.
        await(describe = { "collectors never converged on Ready; first=$seenByFirst second=$seenBySecond" }) {
            seenByFirst.lastOrNull() is UpdateState.Ready && seenBySecond.lastOrNull() is UpdateState.Ready
        }

        // The one progression this run emits. Downloading appears exactly once: runDownload() posts
        // Downloading(update, 0, size) before the transfer starts, and a 1 KiB payload is a single
        // DOWNLOAD_CHUNK_BYTES read, so shouldEmitProgress fires only its final (done >= total)
        // call — which is the Verifying transition. Every state past Checking carries the same
        // AvailableUpdate instance nextStateAfterCheck built once, so it is recoverable from Ready.
        val ready = repo.state.value as UpdateState.Ready
        val canonical = listOf(
            UpdateState.Idle,
            UpdateState.Checking,
            UpdateState.Available(ready.update),
            UpdateState.Downloading(ready.update, 0L, payload.size.toLong()),
            UpdateState.Verifying(ready.update),
            ready,
        )
        assertTrue(seenByFirst.isNotEmpty())
        assertTrue(seenBySecond.isNotEmpty())
        assertSubsequenceOf(canonical, seenByFirst, "first collector")
        assertSubsequenceOf(canonical, seenBySecond, "second collector")
        assertEquals(ready, seenByFirst.last())
        assertEquals(ready, seenBySecond.last())
    }

    /**
     * Regression guard: [UpdateRepository.check] used to capture `state` once, before its network
     * call, then unconditionally write that stale snapshot back once the call returned — so a
     * download that ran to completion *while* the check was in flight got its finished
     * [UpdateState.Ready] clobbered by a resurrected, no-longer-true [UpdateState.Downloading]. The
     * fix re-reads `state` at the moment the result is applied, not when the check started.
     */
    @Test
    fun aCheckInFlightWhileADownloadCompletesNeverStompsTheResultingReadyState() {
        val payload = Random(12).nextBytes(64 * 1024)
        val sha256 = sha256Hex(payload)
        val downloadGate = CompletableDeferred<Unit>()
        val downloaderClient = HttpClient(MockEngine { downloadGate.await(); respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }

        var checkRequestCount = 0
        val checkStarted = CompletableDeferred<Unit>()
        val checkGate = CompletableDeferred<Unit>()
        val checkerClient = HttpClient(
            MockEngine {
                checkRequestCount++
                if (checkRequestCount == 2) {
                    checkStarted.complete(Unit)
                    checkGate.await()
                }
                respond(
                    releaseJson("2.0.0", "Keryx-2.0.0-macos-arm64.zip", "https://release-assets.githubusercontent.com/x.zip", payload.size, sha256),
                    HttpStatusCode.OK,
                )
            },
        ) { expectSuccess = false }

        val repo = UpdateRepository(
            checker = UpdateChecker(checkerClient, currentVersion = "1.0.0", repoSlug = "owner/repo", location = WRITABLE_MAC_LOCATION),
            downloader = UpdateDownloader(downloaderClient),
            installer = noOpInstaller(),
            notificationCenter = NotificationCenter(),
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
        )

        // First check (request #1, ungated) reaches Available; starting the download then parks
        // state at Downloading(0, total) — synchronously, before the gated HTTP call — until
        // downloadGate is released below.
        runBlocking { repo.check() }
        repo.startDownload()
        awaitState(repo) { it is UpdateState.Downloading }

        // A second check begins while state is still that Downloading snapshot: it captures it as
        // "before", then blocks on request #2's gate before it can apply anything.
        val checkJob = trackedScope().launch { repo.check() }
        runBlocking { withTimeout(5_000) { checkStarted.await() } }

        // The download now runs to completion *while the second check is still in flight* — this
        // is the race: state moves on to Ready behind the in-flight check's back.
        downloadGate.complete(Unit)
        awaitState(repo) { it is UpdateState.Ready }

        // Only now does the second check's network call return and its result get applied.
        checkGate.complete(Unit)
        runBlocking { withTimeout(5_000) { checkJob.join() } }

        val finalState = repo.state.value
        assertIs<UpdateState.Ready>(finalState, "a check in flight during a download must not resurrect a stale pre-check state")
        assertEquals("2.0.0", finalState.update.version)
        assertContentEqualsFile(payload, finalState.filePath)
    }

    /**
     * Regression guard: check() must fold installer.canInstall(plan) into
     * AvailableUpdate.installable, so a plan that's technically self-replaceable/runnable but the
     * platform actual currently refuses (Android consent, most notably) doesn't leave the Updates
     * tab/tray showing an enabled "Download" that startDownload() then silently no-ops on.
     */
    @Test
    fun checkFoldsInstallerCanInstallIntoAvailableUpdate() {
        val repo = UpdateRepository(
            checker = checkerFor { """{"tag_name":"v2.0.0","html_url":"https://ex.com/2.0.0","prerelease":false,"draft":false,"assets":[{"name":"Keryx-2.0.0-macos-arm64.zip","browser_download_url":"https://release-assets.githubusercontent.com/x.zip","size":1,"digest":"sha256:${"a".repeat(64)}","state":"uploaded"}]}""" },
            downloader = UpdateDownloader(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) { expectSuccess = false }),
            installer = noOpInstaller(canInstall = false),
            notificationCenter = NotificationCenter(),
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
        )

        runBlocking { repo.check() }

        val available = repo.state.value
        assertIs<UpdateState.Available>(available)
        assertIs<UpdatePlan.SelfReplace>(available.update.plan) // the plan itself would self-replace...
        assertFalse(available.update.installable) // ...but the installer refuses right now
    }

    /**
     * Regression guard for the `noOpInstaller(canInstall = false)` seam itself: startDownload()'s
     * own gate (`if (!canInstall(update.plan)) return@withLock`) must not download a single byte
     * when the installer refuses — not just leave AvailableUpdate.installable == false for the UI
     * to read (see checkFoldsInstallerCanInstallIntoAvailableUpdate above).
     */
    @Test
    fun startDownloadNeverDownloadsWhenTheInstallerCannotInstall() {
        val payload = Random(34).nextBytes(1024)
        val sha256 = sha256Hex(payload)
        var downloadRequestCount = 0
        val downloaderClient = HttpClient(MockEngine { downloadRequestCount++; respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }
        val repo = UpdateRepository(
            checker = checkerFor { releaseJson("2.0.0", "Keryx-2.0.0-macos-arm64.zip", "https://release-assets.githubusercontent.com/x.zip", payload.size, sha256) },
            downloader = UpdateDownloader(downloaderClient),
            installer = noOpInstaller(canInstall = false),
            notificationCenter = NotificationCenter(),
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
        )
        runBlocking { repo.check() }
        assertIs<UpdateState.Available>(repo.state.value)

        repo.startDownload()

        // No completion signal to await (nothing should ever start), so give any wrongly-launched
        // download a real chance to have issued its request before asserting it didn't.
        runBlocking { delay(200) }
        assertEquals(0, downloadRequestCount)
        assertIs<UpdateState.Available>(repo.state.value)
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

    // --- performPrimaryAction(): the single dispatcher the tray/notification-center row calls ---

    @Test
    fun performPrimaryActionOnAvailableStartsTheDownload() {
        val payload = Random(30).nextBytes(64 * 1024)
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

        repo.performPrimaryAction()

        awaitState(repo) { it is UpdateState.Ready }
    }

    @Test
    fun performPrimaryActionOnReadyInstalls() {
        val installer = GatedInstaller(InstallLaunchResult.Launched)
        val repo = readyRepo(installer, seed = 31)

        repo.performPrimaryAction()

        runBlocking { withTimeout(5_000) { installer.started.await() } }
        awaitState(repo) { it is UpdateState.Installing }
    }

    @Test
    fun performPrimaryActionOnAFailedDownloadRetriesIt() {
        val payload = Random(32).nextBytes(64 * 1024)
        val sha256 = sha256Hex(payload)
        var requestCount = 0
        val downloaderClient = HttpClient(
            MockEngine {
                requestCount++
                if (requestCount == 1) respond("", HttpStatusCode.InternalServerError) else respond(payload, HttpStatusCode.OK)
            },
        ) { expectSuccess = false }
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
        awaitState(repo) { it is UpdateState.Failed }
        assertEquals(UpdateStage.DOWNLOAD, (repo.state.value as UpdateState.Failed).exception.stage)

        repo.performPrimaryAction()

        awaitState(repo) { it is UpdateState.Ready }
        assertEquals(2, requestCount)
    }

    @Test
    fun performPrimaryActionOnACheckFailureWithNoUpdateReChecks() {
        var requestCount = 0
        val checkerClient = HttpClient(
            MockEngine {
                requestCount++
                if (requestCount == 1) {
                    respondError(HttpStatusCode.InternalServerError)
                } else {
                    respond(
                        releaseJson("2.0.0", "Keryx-2.0.0-macos-arm64.zip", "https://release-assets.githubusercontent.com/x.zip", 1_000, "a".repeat(64)),
                        HttpStatusCode.OK,
                    )
                }
            },
        ) { expectSuccess = false }
        val repo = UpdateRepository(
            checker = UpdateChecker(checkerClient, currentVersion = "1.0.0", repoSlug = "owner/repo", location = WRITABLE_MAC_LOCATION),
            downloader = UpdateDownloader(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) { expectSuccess = false }),
            installer = noOpInstaller(),
            notificationCenter = NotificationCenter(),
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
        )
        runBlocking { repo.check() }
        val failed = repo.state.value
        assertIs<UpdateState.Failed>(failed)
        assertEquals(null, failed.update)
        assertEquals(UpdateStage.CHECK, failed.exception.stage)

        repo.performPrimaryAction()

        awaitState(repo) { it is UpdateState.Available }
        assertEquals(2, requestCount)
    }

    /**
     * The states [performPrimaryAction] deliberately treats as having no action of its own — see
     * its own KDoc. Covers a representative state reachable purely from construction (Idle) and one
     * only reachable mid-download (Downloading), rather than every state in the sealed hierarchy.
     */
    @Test
    fun performPrimaryActionIsANoOpForStatesWithNoActionOfTheirOwn() {
        var checkRequestCount = 0
        var downloadRequestCount = 0
        val payload = Random(33).nextBytes(64 * 1024)
        val downloadGate = CompletableDeferred<Unit>()
        val downloaderClient = HttpClient(MockEngine { downloadRequestCount++; downloadGate.await(); respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }
        val checkerClient = HttpClient(
            MockEngine {
                checkRequestCount++
                respond(
                    releaseJson("2.0.0", "Keryx-2.0.0-macos-arm64.zip", "https://release-assets.githubusercontent.com/x.zip", payload.size, sha256Hex(payload)),
                    HttpStatusCode.OK,
                )
            },
        ) { expectSuccess = false }
        val repo = UpdateRepository(
            checker = UpdateChecker(checkerClient, currentVersion = "1.0.0", repoSlug = "owner/repo", location = WRITABLE_MAC_LOCATION),
            downloader = UpdateDownloader(downloaderClient),
            installer = noOpInstaller(),
            notificationCenter = NotificationCenter(),
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
        )

        // Idle: brand new repository, nothing has ever run.
        assertEquals(UpdateState.Idle, repo.state.value)
        repo.performPrimaryAction()
        assertEquals(0, checkRequestCount, "Idle must be a no-op, not an implicit check()")
        assertEquals(UpdateState.Idle, repo.state.value)

        // Downloading: parked mid-download via downloadGate.
        runBlocking { repo.check() }
        repo.startDownload()
        awaitState(repo) { it is UpdateState.Downloading }
        val requestsWhileDownloading = downloadRequestCount
        repo.performPrimaryAction()
        assertEquals(requestsWhileDownloading, downloadRequestCount, "Downloading must be a no-op, not a second download")
        assertIs<UpdateState.Downloading>(repo.state.value)

        downloadGate.complete(Unit)
        awaitState(repo) { it is UpdateState.Ready }
    }

    // --- Release watch: a release found with no asset for this install form yet ---
    //
    // The release workflow publishes the GitHub release before attaching the built packages, so a
    // check landing in that gap must not offer OpenReleasePage as a durable verdict, nor post a
    // notification for it — see UpdateInstallPolicy.kt's awaitsReleaseAsset KDoc and
    // UpdateRepository's own runCheck/recordWatchOutcome/startReleaseWatch KDoc.

    @Test
    fun newVersionWithNoAssetYetPostsNoNotificationAndEntersTheReleaseWatch() {
        val notificationCenter = NotificationCenter()
        val repo = UpdateRepository(
            checker = checkerFor { releaseJsonNoAssets("2.0.0") },
            downloader = UpdateDownloader(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) { expectSuccess = false }),
            installer = noOpInstaller(),
            notificationCenter = notificationCenter,
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
        )

        val outcome = runBlocking { repo.check() }

        assertEquals(UpdateCheckOutcome.AWAITING_RELEASE, outcome)
        assertEquals(UpdateState.UpToDate, repo.state.value)
        assertTrue(notificationCenter.items.value.isEmpty())
    }

    @Test
    fun whenTheAssetLaterAppearsTheWatchNoticesAndNotifiesExactlyOnce() {
        val payload = Random(41).nextBytes(1024)
        val sha256 = sha256Hex(payload)
        val assetReady = AtomicInteger(0) // 0 while absent; set to 1 once the test flips it
        val notificationCenter = NotificationCenter()
        val seenStates = CopyOnWriteArrayList<UpdateState>()
        val repo = UpdateRepository(
            checker = checkerFor {
                if (assetReady.get() == 0) {
                    releaseJsonNoAssets("2.0.0")
                } else {
                    releaseJson("2.0.0", "Keryx-2.0.0-macos-arm64.zip", "https://release-assets.githubusercontent.com/x.zip", payload.size, sha256)
                }
            },
            downloader = UpdateDownloader(HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }),
            installer = noOpInstaller(),
            notificationCenter = notificationCenter,
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
            releaseWatchIntervalMs = 20L,
        )
        val collectorScope = trackedScope()
        val subscribed = CompletableDeferred<Unit>()
        collectorScope.launch {
            repo.state.onSubscription { subscribed.complete(Unit) }.collect { seenStates.add(it) }
        }
        runBlocking { withTimeout(5_000) { subscribed.await() } }

        val outcome = runBlocking { repo.check() }
        assertEquals(UpdateCheckOutcome.AWAITING_RELEASE, outcome)
        assertEquals(UpdateState.UpToDate, repo.state.value)

        // The initial check above is user-triggered (quiet = false) and legitimately flashes
        // Checking on its way from Idle to UpToDate — only what happens *after* it, i.e. purely the
        // watch's own quiet retries, must never show it.
        seenStates.clear()

        assetReady.set(1)
        awaitState(repo) { it is UpdateState.Available && it.update.installable }
        await(describe = { "notification never arrived after the asset appeared" }) {
            notificationCenter.items.value.isNotEmpty()
        }

        val items = notificationCenter.items.value
        assertEquals(1, items.size, "exactly one row, not one per poll")
        assertEquals("updateAvailable:2.0.0", items.single().message)
        assertEquals(AppNotificationAction.ShowSettingsTab("updates"), items.single().action)

        // The quiet retries that found nothing must never have flashed UpdateState.Checking — that
        // flicker is reserved for a check the user actually asked for (see runCheck's own KDoc on
        // `quiet`).
        assertFalse(seenStates.contains(UpdateState.Checking), "observed: $seenStates")
    }

    @Test
    fun releaseWithdrawnDuringTheWatchDoesNotEndItAndReappearingWithAnAssetStillNotifies() {
        // Simulates a hand-driven rebuild: the release is briefly unpublished/recreated (phase 1,
        // reported as UpToDate — this checker's currentVersion "1.0.0" makes a v1.0.0 release look
        // like nothing newer at all) before it comes back with the asset attached (phase 2).
        val payload = Random(42).nextBytes(1024)
        val sha256 = sha256Hex(payload)
        val phase = AtomicInteger(0)
        val requestsByPhase = CopyOnWriteArrayList<Int>()
        val notificationCenter = NotificationCenter()
        val repo = UpdateRepository(
            checker = checkerFor {
                val p = phase.get()
                requestsByPhase.add(p)
                when (p) {
                    0 -> releaseJsonNoAssets("2.0.0")
                    1 -> releaseJson("1.0.0", "Keryx-1.0.0-macos-arm64.zip", "https://x/1.0.0.zip", 1, "a".repeat(64))
                    else -> releaseJson("2.0.0", "Keryx-2.0.0-macos-arm64.zip", "https://release-assets.githubusercontent.com/x.zip", payload.size, sha256)
                }
            },
            downloader = UpdateDownloader(HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }),
            installer = noOpInstaller(),
            notificationCenter = notificationCenter,
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
            releaseWatchIntervalMs = 20L,
        )

        val outcome = runBlocking { repo.check() }
        assertEquals(UpdateCheckOutcome.AWAITING_RELEASE, outcome)

        phase.set(1)
        await(describe = { "watch never re-polled the withdrawn release; seen phases: $requestsByPhase" }) {
            requestsByPhase.contains(1)
        }
        assertEquals(UpdateState.UpToDate, repo.state.value, "still watching; nothing to show yet")
        assertTrue(notificationCenter.items.value.isEmpty())

        phase.set(2)
        awaitState(repo) { it is UpdateState.Available && it.update.installable }
        await(describe = { "notification never arrived after the asset reappeared" }) {
            notificationCenter.items.value.isNotEmpty()
        }
        assertEquals(1, notificationCenter.items.value.size)
        assertEquals(AppNotificationAction.ShowSettingsTab("updates"), notificationCenter.items.value.single().action)
    }

    @Test
    fun releaseWithdrawnDuringTheWatchDoesNotStopItEvenOnANetworkFailure() {
        val phase = AtomicInteger(0) // 0 = no asset; 1 = transient check failure
        val phase1RequestCount = AtomicInteger(0)
        val notificationCenter = NotificationCenter()
        val downloaderClient = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }) { expectSuccess = false }
        val checkerClient = HttpClient(
            MockEngine {
                if (phase.get() == 0) {
                    respond(releaseJsonNoAssets("2.0.0"), HttpStatusCode.OK)
                } else {
                    phase1RequestCount.incrementAndGet()
                    respondError(HttpStatusCode.InternalServerError)
                }
            },
        ) { expectSuccess = false }
        val repo = UpdateRepository(
            checker = UpdateChecker(checkerClient, currentVersion = "1.0.0", repoSlug = "owner/repo", location = WRITABLE_MAC_LOCATION),
            downloader = UpdateDownloader(downloaderClient),
            installer = noOpInstaller(),
            notificationCenter = notificationCenter,
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
            releaseWatchIntervalMs = 20L,
        )

        val outcome = runBlocking { repo.check() }
        assertEquals(UpdateCheckOutcome.AWAITING_RELEASE, outcome)

        phase.set(1)
        // A transient failure during a quiet poll must not surface as UpdateState.Failed (see
        // runCheck's own KDoc) nor end the watch — both would be visible regressions a user could
        // stumble on. Wait for at least one such failing poll to have actually happened before
        // asserting on its (lack of) effect.
        await(describe = { "watch never re-polled after the endpoint started failing" }) {
            phase1RequestCount.get() > 0
        }
        Thread.sleep(100) // let that poll's own state-handling (a no-op) finish settling

        assertEquals(UpdateState.UpToDate, repo.state.value, "a quiet poll's own failure must not surface as Failed")
        assertTrue(notificationCenter.items.value.isEmpty())
    }

    @Test
    fun watchGivesUpAfterItsAttemptBudgetWithoutEverNotifying() {
        val requestCount = AtomicInteger(0)
        val notificationCenter = NotificationCenter()
        val repo = UpdateRepository(
            checker = checkerFor { requestCount.incrementAndGet(); releaseJsonNoAssets("2.0.0") },
            downloader = UpdateDownloader(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) { expectSuccess = false }),
            installer = noOpInstaller(),
            notificationCenter = notificationCenter,
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
            releaseWatchIntervalMs = 0L,
        )

        val outcome = runBlocking { repo.check() }
        assertEquals(UpdateCheckOutcome.AWAITING_RELEASE, outcome)

        // The very first check (this call) already consumes attempt #1 of the budget, so the
        // total request count when the watch gives up is exactly the budget itself, not budget + 1.
        val expectedTotal = UPDATE_RELEASE_WATCH_MAX_ATTEMPTS
        await(timeoutMs = 30_000, describe = { "release watch never gave up; requestCount=$requestCount" }) {
            requestCount.get() >= expectedTotal
        }
        // Let the request that pushed the budget over finish its own bookkeeping and the watch
        // loop actually exit, then confirm no further request follows.
        Thread.sleep(200)
        val countAfterSettling = requestCount.get()
        assertEquals(expectedTotal, countAfterSettling, "the watch kept polling past its own budget")
        assertTrue(notificationCenter.items.value.isEmpty())
        assertEquals(UpdateState.UpToDate, repo.state.value)
    }

    /**
     * Regression guard for a reset bug that would otherwise be invisible from outside this class:
     * if [UpdateRepository]'s internal watch-attempt counter were left non-zero after the budget
     * above ran out, a later, entirely unrelated [UpdateStatus.UpToDate] (the ordinary
     * `updateCheckIntervalHours` schedule finding nothing new, long after this environment gave up
     * on the earlier release) would be mistaken for "still watching" and silently resume polling
     * every `releaseWatchIntervalMs` forever.
     */
    @Test
    fun afterTheWatchBudgetIsExhaustedAPlainUpToDateCheckIsNotMistakenForStillWatching() {
        val phase = AtomicInteger(0) // 0 = no asset (drives the watch to exhaustion); 1 = plain up to date
        val requestCount = AtomicInteger(0)
        val repo = UpdateRepository(
            checker = checkerFor {
                requestCount.incrementAndGet()
                if (phase.get() == 0) releaseJsonNoAssets("2.0.0") else releaseJson("1.0.0", "x", "https://x/1.0.0.zip", 1, "a".repeat(64))
            },
            downloader = UpdateDownloader(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) { expectSuccess = false }),
            installer = noOpInstaller(),
            notificationCenter = NotificationCenter(),
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
            releaseWatchIntervalMs = 0L,
        )

        val firstOutcome = runBlocking { repo.check() }
        assertEquals(UpdateCheckOutcome.AWAITING_RELEASE, firstOutcome)

        // The very first check (this call) already consumes attempt #1 of the budget, so the
        // total request count when the watch gives up is exactly the budget itself, not budget + 1.
        val expectedTotal = UPDATE_RELEASE_WATCH_MAX_ATTEMPTS
        await(timeoutMs = 30_000, describe = { "release watch never gave up; requestCount=$requestCount" }) {
            requestCount.get() >= expectedTotal
        }
        Thread.sleep(200) // let the watch loop's own last iteration finish and truly exit

        phase.set(1)
        val laterOutcome = runBlocking { repo.check() }
        assertEquals(
            UpdateCheckOutcome.CONCLUSIVE,
            laterOutcome,
            "a plain UpToDate after the watch already gave up must not be reinterpreted as still watching",
        )
        assertEquals(UpdateState.UpToDate, repo.state.value)
    }

    // --- Regression: a public check and a quiet release-watch poll must never race to apply a
    // stale result over each other's — see UpdateRepository's own checkMutex KDoc.

    /**
     * Regression guard: before checkMutex, runCheck() only held [mutex] for the brief "start"/
     * "apply"/"watch bookkeeping" steps, never across checker.check() itself. A quiet release-watch
     * poll that started first (and so saw no asset) could still finish *after* a later, user-
     * triggered check that found one, clobbering the fresh Available with its own stale UpToDate.
     */
    @Test
    fun aQuietWatchPollInFlightNeverOverwritesAPublicCheckThatFoundTheAsset() {
        val payload = Random(50).nextBytes(1024)
        val sha256 = sha256Hex(payload)
        val requestCount = AtomicInteger(0)
        val assetReady = AtomicInteger(0) // 0 while absent; set to 1 once the test flips it
        val quietPollParked = CompletableDeferred<Unit>()
        val quietGate = CompletableDeferred<Unit>()
        val notificationCenter = NotificationCenter()
        val checkerClient = HttpClient(
            MockEngine { _ ->
                val n = requestCount.incrementAndGet()
                // Snapshot the body *before* parking, from assetReady as it stood when this request
                // was actually issued — if this read happened after quietGate.await() instead, the
                // parked poll would pick up the asset that only appears later, defeating the very
                // race this test means to reproduce (a stale, already-in-flight result applied late).
                val body = if (assetReady.get() == 0) {
                    releaseJsonNoAssets("2.0.0")
                } else {
                    releaseJson("2.0.0", "Keryx-2.0.0-macos-arm64.zip", "https://release-assets.githubusercontent.com/x.zip", payload.size, sha256)
                }
                if (n == 2) {
                    quietPollParked.complete(Unit)
                    quietGate.await()
                }
                respond(body, HttpStatusCode.OK)
            },
        ) { expectSuccess = false }
        val repo = UpdateRepository(
            checker = UpdateChecker(checkerClient, currentVersion = "1.0.0", repoSlug = "owner/repo", location = WRITABLE_MAC_LOCATION),
            downloader = UpdateDownloader(HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }),
            installer = noOpInstaller(),
            notificationCenter = notificationCenter,
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
            releaseWatchIntervalMs = 1_500L,
        )

        // Starts the release watch: request #1 (the public check itself) finds no asset yet.
        val firstOutcome = runBlocking { repo.check() }
        assertEquals(UpdateCheckOutcome.AWAITING_RELEASE, firstOutcome)

        // The watch's own first quiet poll (request #2) is now parked mid-network-call, body already
        // snapshotted as "no asset".
        runBlocking { withTimeout(5_000) { quietPollParked.await() } }
        assetReady.set(1)

        // A second, user-triggered check would — without checkMutex — be free to race the parked
        // poll's own network call and apply its Available result first. With checkMutex it instead
        // waits for the poll to fully finish (including its own "apply" step) before even issuing
        // its own request.
        val secondCheck = trackedScope().launch { repo.check() }
        runBlocking { delay(200) } // give a wrongly-concurrent request every chance to have fired

        quietGate.complete(Unit)
        runBlocking { withTimeout(5_000) { secondCheck.join() } }

        val state = repo.state.value
        assertIs<UpdateState.Available>(state, "the parked quiet poll's stale UpToDate must not win over the later check's Available")
        assertTrue(state.update.installable)
        assertEquals(1, notificationCenter.items.value.size)
    }

    /**
     * Regression guard: the quiet-failure branch of runCheck() used to call
     * `recordWatchOutcome(stillWatching = true)` unconditionally, so a poll that had already been
     * queued behind a public check — one that found the asset and ended the watch — would resurrect
     * that watch on its own subsequent transient failure, even though nothing is being waited for
     * any more.
     */
    @Test
    fun aQuietPollFailingAfterAPublicCheckEndedTheWatchDoesNotRestartIt() {
        val phase = AtomicInteger(0) // 0 = no asset; 1 = asset present; 2 = transient failure
        val requestCount = AtomicInteger(0)
        val payload = Random(51).nextBytes(1024)
        val sha256 = sha256Hex(payload)
        val notificationCenter = NotificationCenter()
        val checkerClient = HttpClient(
            MockEngine {
                requestCount.incrementAndGet()
                when (phase.get()) {
                    0 -> respond(releaseJsonNoAssets("2.0.0"), HttpStatusCode.OK)
                    1 -> respond(
                        releaseJson("2.0.0", "Keryx-2.0.0-macos-arm64.zip", "https://release-assets.githubusercontent.com/x.zip", payload.size, sha256),
                        HttpStatusCode.OK,
                    )
                    else -> respondError(HttpStatusCode.InternalServerError)
                }
            },
        ) { expectSuccess = false }
        val repo = UpdateRepository(
            checker = UpdateChecker(checkerClient, currentVersion = "1.0.0", repoSlug = "owner/repo", location = WRITABLE_MAC_LOCATION),
            downloader = UpdateDownloader(HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }),
            installer = noOpInstaller(),
            notificationCenter = notificationCenter,
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = WRITABLE_MAC_LOCATION,
            cacheDirOverride = newTempDir(),
            releaseWatchIntervalMs = 1_000L,
        )

        // Starts the watch.
        val firstOutcome = runBlocking { repo.check() }
        assertEquals(UpdateCheckOutcome.AWAITING_RELEASE, firstOutcome)

        // A public check immediately finds the asset and ends the watch (attempt counter reset).
        phase.set(1)
        val secondOutcome = runBlocking { repo.check() }
        assertEquals(UpdateCheckOutcome.CONCLUSIVE, secondOutcome)
        assertIs<UpdateState.Available>(repo.state.value)

        // The watch's loop is still alive as a coroutine (its own `while (isActive)` has not yet
        // rechecked the outcome), asleep in its next `delay(releaseWatchIntervalMs)` — so exactly
        // one more quiet poll is still going to fire, and now hits a transient failure.
        phase.set(2)
        val requestsBeforeFailure = requestCount.get()
        await(timeoutMs = 5_000, describe = { "the already-scheduled quiet poll never fired; requestCount=$requestCount" }) {
            requestCount.get() > requestsBeforeFailure
        }

        // The regression: with `stillWatching` hardcoded to `true`, that one failure would resurrect
        // the watch and it would keep polling every releaseWatchIntervalMs forever. Fixed, this
        // single already-in-flight poll finds `releaseWatchAttempts == 0` (the public check reset
        // it) and stops the loop instead of continuing it — so waiting several more intervals must
        // show no further growth.
        Thread.sleep(2_500)
        assertEquals(
            requestsBeforeFailure + 1,
            requestCount.get(),
            "a quiet poll's own transient failure must not resurrect a watch the public check already ended",
        )
        assertIs<UpdateState.Available>(repo.state.value)
    }

    // --- Regression: install forms that must notify immediately and never enter the release watch ---
    //
    // awaitsReleaseAsset requires asset == null, so any of these — even with a release that
    // genuinely carries no asset for this install form — must still resolve to an immediate OpenUrl
    // notification and UpdateCheckOutcome.CONCLUSIVE, never UpdateCheckOutcome.AWAITING_RELEASE.

    @Test
    fun macOsTranslocatedNotifiesImmediatelyAndNeverWatches() {
        assertImmediateOpenUrlNoWatch(WRITABLE_MAC_LOCATION.copy(translocated = true), installerCanInstall = true)
    }

    @Test
    fun macOsUnwritableParentNotifiesImmediatelyAndNeverWatches() {
        assertImmediateOpenUrlNoWatch(WRITABLE_MAC_LOCATION.copy(parentWritable = false), installerCanInstall = true)
    }

    @Test
    fun linuxPackageInstallNotifiesImmediatelyAndNeverWatchesRegardlessOfAsset() {
        val linuxDeb = InstallLocation(InstallKind.LINUX_PACKAGE, appRoot = null, launcherPath = null, parentWritable = false, translocated = false)
        assertImmediateOpenUrlNoWatch(linuxDeb, installerCanInstall = true)
    }

    @Test
    fun assetPresentButInstallerRefusingNotifiesImmediatelyAndNeverWatches() {
        // The Android install-unknown-apps-consent shape: the release has everything this install
        // form needs, but the platform actual currently refuses it — a real, immediate answer, not
        // "still being uploaded".
        assertImmediateOpenUrlNoWatch(WRITABLE_MAC_LOCATION, installerCanInstall = false)
    }

    /** Unlike [noOpInstaller] (a constant answer regardless of [UpdatePlan]), this mirrors what a
     * real [UpdateInstaller] does — [DesktopUpdateInstaller.canInstall] unconditionally refuses
     * [UpdatePlan.OpenReleasePage]/[UpdatePlan.NotOffered] regardless of anything else — so
     * [consent] (standing in for a platform-level gate like Android's install-unknown-apps consent)
     * only ever matters for a plan that would otherwise be installable. Needed here specifically:
     * [noOpInstaller]'s constant `true` would make a translocated/unwritable/deb location's
     * [UpdatePlan.OpenReleasePage] look installable, which a real [UpdateInstaller] never would. */
    private fun consentGatedInstaller(consent: Boolean) = object : UpdateInstaller {
        override fun canInstall(plan: UpdatePlan) = plan.isInstallable && consent
        override suspend fun install(filePath: String, update: AvailableUpdate) = InstallLaunchResult.Failed("not used")
    }

    /** Shared body for the four regression tests above: a release with a real, present asset for
     * [location]'s own install form (an unrelated asset when [location]'s kind never matches one at
     * all, e.g. Linux deb — irrelevant to it either way, since it always opens the release page). */
    private fun assertImmediateOpenUrlNoWatch(location: InstallLocation, installerCanInstall: Boolean) {
        val payload = Random(43).nextBytes(16)
        val sha256 = sha256Hex(payload)
        val notificationCenter = NotificationCenter()
        val repo = UpdateRepository(
            checker = checkerFor { releaseJson("2.0.0", "Keryx-2.0.0-macos-arm64.zip", "https://release-assets.githubusercontent.com/x.zip", payload.size, sha256) },
            downloader = UpdateDownloader(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) { expectSuccess = false }),
            installer = consentGatedInstaller(installerCanInstall),
            notificationCenter = notificationCenter,
            notificationMessages = RecordingNotificationMessages(),
            scope = trackedScope(),
            location = location,
            cacheDirOverride = newTempDir(),
        )

        val outcome = runBlocking { repo.check() }

        assertEquals(UpdateCheckOutcome.CONCLUSIVE, outcome)
        val notification = notificationCenter.items.value.singleOrNull()
        assertIs<AppNotificationAction.OpenUrl>(notification?.action)
        assertEquals("https://ex.com/2.0.0", (notification?.action as AppNotificationAction.OpenUrl).url)
        val state = repo.state.value
        assertIs<UpdateState.Available>(state)
        assertFalse(state.update.installable)
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
