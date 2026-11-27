package works.merc.keryx.app

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.OAuthCallbackParams
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OPML_XML = """<?xml version="1.0"?>
<opml version="2.0"><body><outline text="Feed" xmlUrl="https://ex.com/feed"/></body></opml>"""

/** [LaunchArgDispatcher]: each launch-arg kind reaches its own handler, anything else is ignored. */
class LaunchArgDispatcherTest {

    private lateinit var driver: SqlDriver
    private lateinit var koin: Koin
    private lateinit var callbacks: MutableSharedFlow<OAuthCallbackParams>
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
            driver = driver,
        )
        val folderRepository = FolderRepository(db, feedRepository, SyncScheduler {}, clock, Dispatchers.Unconfined)
        val tagRepository = TagRepository(db, SyncScheduler {}, clock, Dispatchers.Unconfined)
        val transfer = OpmlTransfer(feedRepository, folderRepository, tagRepository, OpmlImporter(feedRepository, folderRepository, tagRepository))
        callbacks = MutableSharedFlow(replay = 8)
        koin = koinApplication {
            modules(
                module {
                    single { callbacks }
                    single<CoroutineScope> { appScope }
                    single { OpmlTransferController(transfer, appScope, Dispatchers.Unconfined) }
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

    private val controller get() = koin.get<OpmlTransferController>()

    @Test
    fun `an OAuth callback URI is parsed and emitted on the callback flow`() {
        LaunchArgDispatcher(koin).dispatchLaunchArg("keryx://oauth/callback?code=abc&state=xyz")

        assertEquals(
            listOf(OAuthCallbackParams(code = "abc", state = "xyz", error = null, errorDescription = null)),
            callbacks.replayCache,
        )
        assertNull(controller.pendingRequest.value)
    }

    @Test
    fun `an OAuth error callback carries the error fields`() {
        LaunchArgDispatcher(koin).dispatchLaunchArg("keryx://oauth/callback?error=access_denied&error_description=nope")

        val params = callbacks.replayCache.single()
        assertEquals("access_denied", params.error)
        assertEquals("nope", params.errorDescription)
        assertNull(params.code)
    }

    @Test
    fun `an opml path is handed to the OPML handler and not the OAuth flow`() {
        val file = File.createTempFile("keryx-launch", ".opml").also { tempFiles += it }
        file.writeText(OPML_XML)

        LaunchArgDispatcher(koin).dispatchLaunchArg(file.absolutePath)

        awaitConditionBlocking { controller.pendingRequest.value != null }
        assertEquals(OpmlRequest.ImportDocument(OPML_XML), controller.pendingRequest.value)
        assertTrue(callbacks.replayCache.isEmpty())
    }

    @Test
    fun `other arguments are ignored`() {
        val dispatcher = LaunchArgDispatcher(koin)
        dispatcher.dispatchLaunchArg("--verbose")
        dispatcher.dispatchLaunchArg("https://example.com/feed.xml")
        dispatcher.dispatchLaunchArg("notes.txt")

        assertTrue(callbacks.replayCache.isEmpty())
        assertNull(controller.pendingRequest.value)
        assertTrue(activationRequests.replayCache.isEmpty())
    }
}
