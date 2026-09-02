package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.UpdateDownloader
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.platform.InstallKind
import works.merc.keryx.app.platform.InstallLocation
import works.merc.keryx.app.platform.SelfUpdateCheckSupport
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L

/**
 * Inserts an article directly (bypassing the repository), mirroring `ArticleRepositoryTest`'s own
 * private helper of the same shape.
 */
private fun KeryxDatabase.insertArticle(id: String, feedId: String, publishedAt: Long, cachedAt: Long) {
    articlesQueries.insert(
        id = id, feed_id = feedId, guid = id, url = "https://article/$id", title = "Title $id",
        summary = null, content = null, author = null, published_at = publishedAt,
        thumbnail_url = null, is_read = 0L, read_at = null, is_starred = 0L, starred_at = null,
        cached_at = cachedAt, search_text = "", updated_at = 0L, created_at = 0L,
    )
}

class StartupMaintenanceTasksTest {
    private val dir = FileIO.join(AppDirs.tempDir(), "startup-maintenance-test-${Random.nextInt()}")

    @AfterTest
    fun cleanup() {
        FileIO.delete(FileIO.join(dir, "local_settings.json"))
    }

    /**
     * Wires a real [SettingsRepository] and [ArticleRepository] (against [db]/[driver]) plus a
     * fixed [Clock] into an isolated Koin instance — no `startKoin()`, so nothing leaks between
     * tests or into production DI.
     */
    private fun testKoin(db: KeryxDatabase, driver: app.cash.sqldelight.db.SqlDriver, now: Long): Koin {
        val clock = Clock { now }
        val settingsRepository = SettingsRepository(
            db, LocalSettingsStore(dirOverride = dir), SyncScheduler {}, clock, writeDispatcher = Dispatchers.Unconfined,
        )
        val articleRepository = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, clock, Dispatchers.Unconfined)
        return koinApplication {
            modules(
                module {
                    single { settingsRepository }
                    single { articleRepository }
                    single<Clock> { clock }
                },
            )
        }.koin
    }

    /** 10 recent articles occupy the "latest 10 per feed" protection a real expiry must clear. */
    private fun KeryxDatabase.seedOneFeedWithOneExpirableArticle(now: Long) {
        insertFeed("f1")
        for (i in 0 until 10) insertArticle("recent$i", "f1", publishedAt = 1000L + i, cachedAt = now)
        insertArticle("old", "f1", publishedAt = 0L, cachedAt = now - 3 * ONE_DAY_MS)
    }

    @Test
    fun skipsCleanupWithinTheDailyGate() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            val now = 10 * ONE_DAY_MS
            db.seedOneFeedWithOneExpirableArticle(now)
            val koin = testKoin(db, driver, now)
            koin.get<SettingsRepository>().setCacheRetentionDays(1)
            koin.get<SettingsRepository>().mutateLocalSettings { it.copy(lastCacheCleanupAt = now - 1000) }

            cleanUpArticleCacheIfDue(koin)

            assertNull(db.articlesQueries.getById("old").executeAsOne().deleted_at)
            assertEquals(now - 1000, koin.get<SettingsRepository>().getLocalSettings().lastCacheCleanupAt)
        } finally {
            driver.close()
        }
    }

    @Test
    fun runsCleanupWhenDueAndPersistsTheTimestamp() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            val now = 10 * ONE_DAY_MS
            db.seedOneFeedWithOneExpirableArticle(now)
            val koin = testKoin(db, driver, now)
            koin.get<SettingsRepository>().setCacheRetentionDays(1)
            // lastCacheCleanupAt starts unset (null) — first run, so the gate is due.

            cleanUpArticleCacheIfDue(koin)

            assertNotNull(db.articlesQueries.getById("old").executeAsOne().deleted_at)
            assertEquals(now, koin.get<SettingsRepository>().getLocalSettings().lastCacheCleanupAt)
        } finally {
            driver.close()
        }
    }

    @Test
    fun passesTheConfiguredRetentionDaysThrough() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            val now = 10 * ONE_DAY_MS
            db.seedOneFeedWithOneExpirableArticle(now)
            val koin = testKoin(db, driver, now)
            koin.get<SettingsRepository>().setCacheRetentionDays(null) // unlimited retention

            cleanUpArticleCacheIfDue(koin)

            // Proves the configured value (not a hardcoded default) reached deleteExpiredArticles:
            // deleteExpiredArticles(null) no-ops, so nothing is deleted despite the gate being due.
            assertNull(db.articlesQueries.getById("old").executeAsOne().deleted_at)
        } finally {
            driver.close()
        }
    }

    @Test
    fun checkForUpdateAndNotifySkipsWhenSelfUpdateCheckIsUnsupported() = runTest {
        val koin = koinApplication {
            modules(
                module {
                    single<SelfUpdateCheckSupport> { SelfUpdateCheckSupport { false } }
                },
            )
        }.koin
        // No SettingsRepository/UpdateRepository/NotificationCenter registered: if the guard failed
        // to short-circuit, resolving any of them here would throw and fail this test.

        checkForUpdateAndNotify(koin)
    }

    /**
     * Wires a real [UpdateRepository] (backed by a MockEngine [UpdateChecker] returning
     * [releaseBody]) into an isolated Koin instance, mirroring [testKoin]'s shape. [location] and
     * [installerCanInstall] control the notification action [checkForUpdateAndNotify] ends up with.
     */
    private fun testKoinForUpdateCheck(
        db: KeryxDatabase,
        driver: app.cash.sqldelight.db.SqlDriver,
        now: Long,
        releaseBody: String,
        location: InstallLocation = FAKE_UNSUPPORTED_LOCATION,
        installerCanInstall: Boolean = false,
    ): Koin {
        val clock = Clock { now }
        val settingsRepository = SettingsRepository(
            db, LocalSettingsStore(dirOverride = dir), SyncScheduler {}, clock, writeDispatcher = Dispatchers.Unconfined,
        )
        val notificationCenter = NotificationCenter()
        val checkerClient = HttpClient(MockEngine { respond(releaseBody, HttpStatusCode.OK) }) { expectSuccess = false }
        val checker = UpdateChecker(checkerClient, currentVersion = "1.0.0", repoSlug = "owner/repo", location = location)
        val downloaderClient = HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) { expectSuccess = false }
        val installer = object : UpdateInstaller {
            override fun canInstall(plan: UpdatePlan) = installerCanInstall
            override suspend fun install(filePath: String, update: AvailableUpdate) = InstallLaunchResult.Failed("not used")
        }
        val updateRepository = UpdateRepository(
            checker = checker,
            downloader = UpdateDownloader(downloaderClient),
            installer = installer,
            notificationCenter = notificationCenter,
            notificationMessages = FakeUpdateNotificationMessages(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            location = location,
        )
        return koinApplication {
            modules(
                module {
                    single { settingsRepository }
                    single { notificationCenter }
                    single { updateRepository }
                    single<Clock> { clock }
                    single<SelfUpdateCheckSupport> { SelfUpdateCheckSupport { true } }
                },
            )
        }.koin
    }

    @Test
    fun checkForUpdateAndNotifyCoalescesRepeatedFindsOfTheSameVersion() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            val now = 10 * ONE_DAY_MS
            val koin = testKoinForUpdateCheck(
                db, driver, now,
                releaseBody = """{"tag_name":"v2.0.0","html_url":"https://ex.com/2.0.0","prerelease":false,"draft":false}""",
            )

            checkForUpdateAndNotify(koin)
            checkForUpdateAndNotify(koin)

            assertEquals(1, koin.get<NotificationCenter>().items.value.size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun checkForUpdateAndNotifyOpensSettingsTabWhenAnAssetIsInstallableHere() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            val now = 10 * ONE_DAY_MS
            val sha256 = "a".repeat(64)
            val koin = testKoinForUpdateCheck(
                db, driver, now,
                releaseBody = """
                    {"tag_name":"v2.0.0","html_url":"https://ex.com/2.0.0","prerelease":false,"draft":false,"assets":[
                        {"name":"Keryx-2.0.0-macos-arm64.zip","browser_download_url":"https://dl/mac.zip",
                         "size":1,"digest":"sha256:$sha256","state":"uploaded"}
                    ]}
                """.trimIndent(),
                location = FAKE_MAC_LOCATION,
                installerCanInstall = true,
            )

            checkForUpdateAndNotify(koin)

            val notification = koin.get<NotificationCenter>().items.value.single()
            assertEquals(AppNotificationAction.ShowSettingsTab("updates"), notification.action)
        } finally {
            driver.close()
        }
    }

    @Test
    fun checkForUpdateAndNotifyOpensReleasePageWhenNotInstallableHere() = runTest {
        val (driver, db) = inMemoryDb()
        try {
            val now = 10 * ONE_DAY_MS
            val koin = testKoinForUpdateCheck(
                db, driver, now,
                // No assets in the release at all, and FAKE_UNSUPPORTED_LOCATION can't act on one
                // anyway — either alone would be enough to force this fallback.
                releaseBody = """{"tag_name":"v2.0.0","html_url":"https://ex.com/2.0.0","prerelease":false,"draft":false}""",
            )

            checkForUpdateAndNotify(koin)

            val notification = koin.get<NotificationCenter>().items.value.single()
            val action = notification.action
            assertIs<AppNotificationAction.OpenUrl>(action)
            assertEquals("https://ex.com/2.0.0", action.url)
        } finally {
            driver.close()
        }
    }
}

private class FakeUpdateNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String) = "feedGone:$feedTitle"
    override suspend fun feedUrlChanged(feedTitle: String) = "feedUrlChanged:$feedTitle"
    override suspend fun newArticles(count: Int) = "newArticles:$count"
    override suspend fun syncFailed(exception: KeryxException) = "syncFailed"
    override suspend fun opmlImported(added: Int, failed: Int) = "opmlImported:$added/$failed"
    override suspend fun updateAvailable(version: String) = "updateAvailable:$version"
    override suspend fun updateReadyToInstall(version: String) = "updateReadyToInstall:$version"
}

private val FAKE_MAC_LOCATION = InstallLocation(
    InstallKind.MAC_APP_BUNDLE, appRoot = "/Applications/Keryx.app", launcherPath = null, parentWritable = true, translocated = false,
)
private val FAKE_UNSUPPORTED_LOCATION = InstallLocation(
    InstallKind.DEVELOPMENT, appRoot = null, launcherPath = null, parentWritable = false, translocated = false,
)
