package works.merc.keryx.app.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.platform.SelfUpdateCheckSupport
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        // No SettingsRepository/UpdateChecker/NotificationCenter registered: if the guard failed
        // to short-circuit, resolving any of them here would throw and fail this test.

        checkForUpdateAndNotify(koin)
    }
}
