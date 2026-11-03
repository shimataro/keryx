package works.merc.keryx.app

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.InfoDialogText
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.OpmlImporter
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.domain.TagRepository
import works.merc.keryx.app.presentation.settings.OpmlRequest
import works.merc.keryx.app.presentation.settings.OpmlTransfer
import works.merc.keryx.app.presentation.settings.OpmlTransferController
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TRANSLOCATED =
    "/private/var/folders/xy/abc/T/AppTranslocation/D1E2/d/Keryx.app/Contents/MacOS/Keryx"

private const val OPML = """<?xml version="1.0"?>
<opml version="2.0"><body><outline text="Feed" xmlUrl="https://ex.com/feed"/></body></opml>"""

/** Covers the testable pieces of `StartupTasks.kt`: the translocation warning and the opened-OPML hand-off. */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupTasksTest {

    private lateinit var driver: SqlDriver
    private lateinit var koin: Koin
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val tempFiles = mutableListOf<File>()

    @BeforeTest
    fun setUp() {
        activationRequests.resetReplayCache()
        val (d, db) = inMemoryDb()
        driver = d
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        val clock = Clock { 1000L }
        val articleRepository = ArticleRepository(db, FtsSearch(driver), SyncScheduler {}, clock, Dispatchers.Unconfined)
        val feedRepository = FeedRepository(
            db, FeedFetcher(client), FaviconResolver(client), articleRepository, ftsManagerIndexed(driver),
            SyncScheduler {}, NotificationCenter(), clock, Dispatchers.Unconfined,
            faviconMisses = InMemoryFaviconMissStore(),
        )
        val folderRepository = FolderRepository(db, feedRepository, SyncScheduler {}, clock, Dispatchers.Unconfined)
        val tagRepository = TagRepository(db, SyncScheduler {}, clock, Dispatchers.Unconfined)
        val transfer = OpmlTransfer(feedRepository, folderRepository, tagRepository, OpmlImporter(feedRepository, folderRepository, tagRepository))
        koin = koinApplication {
            modules(
                module {
                    single { OpmlTransferController(transfer, appScope, Dispatchers.Unconfined) }
                    single { NotificationCenter() }
                },
            )
        }.koin
    }

    @AfterTest
    fun tearDown() {
        appScope.cancel()
        driver.close()
        tempFiles.forEach { it.delete() }
        activationRequests.resetReplayCache()
    }

    private val center get() = koin.get<NotificationCenter>()
    private val controller get() = koin.get<OpmlTransferController>()

    // --- warnIfAppTranslocated ---

    @Test
    fun `a translocated path posts exactly one app-translocated warning`() = runBlocking {
        warnIfAppTranslocated(koin, TRANSLOCATED)

        val notification = center.items.value.single()
        assertEquals(AppNotificationLevel.WARNING, notification.level)
        assertEquals(NotificationText.AppTranslocated, notification.text)
        assertEquals(AppNotificationAction.ShowInfoDialog(InfoDialogText.APP_TRANSLOCATED), notification.action)
    }

    @Test
    fun `a normal path posts nothing`() = runBlocking {
        warnIfAppTranslocated(koin, "/Applications/Keryx.app/Contents/MacOS/Keryx")

        assertTrue(center.items.value.isEmpty())
    }

    @Test
    fun `a null path posts nothing`() = runBlocking {
        warnIfAppTranslocated(koin, null)

        assertTrue(center.items.value.isEmpty())
    }

    // --- handleOpenedOpmlFile ---

    @Test
    fun `a readable opened OPML file is requested for import and activates the window`() = runBlocking {
        val file = File.createTempFile("keryx-open", ".opml").also { tempFiles += it }
        file.writeText(OPML)

        handleOpenedOpmlFile(koin, file.absolutePath)

        assertEquals(OpmlRequest.ImportDocument(OPML), controller.pendingRequest.value)
        assertEquals(1, activationRequests.replayCache.size)
        assertTrue(center.items.value.isEmpty())
    }

    @Test
    fun `an unreadable opened OPML file is requested as a null document and still activates`() = runBlocking {
        val missing = File(System.getProperty("java.io.tmpdir"), "keryx-missing-${System.nanoTime()}.opml")

        handleOpenedOpmlFile(koin, missing.absolutePath)

        val request = controller.pendingRequest.value
        assertIs<OpmlRequest.ImportDocument>(request)
        assertNull(request.xml)
        assertNotNull(activationRequests.replayCache.singleOrNull())
    }
}
