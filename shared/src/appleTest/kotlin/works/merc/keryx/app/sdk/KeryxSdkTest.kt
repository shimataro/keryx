package works.merc.keryx.app.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import works.merc.keryx.app.core.DB_FILE_NAME
import works.merc.keryx.app.data.local.DatabaseTooNewException
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.platform.FileSystemExtras
import works.merc.keryx.app.platform.RawSqliteConnection
import works.merc.keryx.app.tempFilePath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KeryxSdkTest {

    private val dir = tempFilePath("keryx-sdk-", "")

    // HomeViewModel's viewModelScope runs on Dispatchers.Main, which has no run loop in a test.
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        AppDirs.rootOverride = null
        FileSystemExtras.deleteRecursively(dir)
    }

    private fun start() = KeryxSdk.start(
        newArticlesText = { count -> "$count new" },
        postOsNotification = { _, _ -> },
        dataDirectory = dir,
    )

    @Test
    fun startKeepsEverythingUnderTheGivenDataDirectory() = runTest {
        val sdk = start()

        assertEquals(dir, AppDirs.appDataDir())
        val dbPath = FileIO.join(dir, DB_FILE_NAME)
        assertEquals(KeryxDatabase.Schema.version, RawSqliteConnection.userVersionOf(dbPath))
        try {
            sdk.prepareSearchIndex()
            assertTrue(sdk.homeViewModel.feeds.value.isEmpty())
            assertTrue(sdk.newAddFeedController().state.value.url.isEmpty())
        } finally {
            sdk.close()
        }
    }

    @Test
    fun theSdkCanBeStartedAgainAfterClose() = runTest {
        start().close()
        val again = start()
        try {
            assertTrue(again.homeViewModel.feeds.value.isEmpty())
        } finally {
            again.close()
        }
    }

    @Test
    fun aDatabaseFromANewerBuildIsReportedByStart() {
        FileIO.writeBytes(FileIO.join(dir, "placeholder"), ByteArray(0))
        RawSqliteConnection.open(FileIO.join(dir, DB_FILE_NAME), create = true).use { it.exec("PRAGMA user_version = 99") }

        assertFailsWith<DatabaseTooNewException> { start() }
    }
}
