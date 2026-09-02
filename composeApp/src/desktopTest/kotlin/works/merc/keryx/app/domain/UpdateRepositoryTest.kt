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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.core.KeryxException
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
}

private fun assertContentEqualsFile(expected: ByteArray, path: String) {
    assertEquals(expected.toList(), File(path).readBytes().toList())
}
