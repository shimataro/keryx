package works.merc.keryx.app.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import works.merc.keryx.app.core.DB_FILE_NAME
import works.merc.keryx.app.data.local.DatabaseTooNewException
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.OAuthCallbackParams
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun aFailedStartReleasesTheDirectoryOverrideAndAllowsAnotherStart() = runTest {
        val dbPath = FileIO.join(dir, DB_FILE_NAME)
        FileIO.writeBytes(FileIO.join(dir, "placeholder"), ByteArray(0))
        RawSqliteConnection.open(dbPath, create = true).use { it.exec("PRAGMA user_version = 99") }
        assertFailsWith<DatabaseTooNewException> { start() }
        assertNull(AppDirs.rootOverride)

        FileIO.delete(dbPath)
        val again = start()
        try {
            assertEquals(dir, AppDirs.appDataDir())
            assertEquals(KeryxDatabase.Schema.version, RawSqliteConnection.userVersionOf(dbPath))
            again.prepareSearchIndexIfAbsent()
        } finally {
            again.close()
        }
        assertNull(AppDirs.rootOverride)
    }

    @Test
    fun anOAuthRedirectWithNobodyWaitingIsReportedUndeliveredAndDropped() = runTest {
        val sdk = start()
        try {
            assertFalse(sdk.handleOAuthRedirect("keryx://oauth2/callback?code=early&state=s"))

            val received = mutableListOf<OAuthCallbackParams>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                sdk.oauthCallbacks.collect { received += it }
            }
            assertTrue(received.isEmpty())
        } finally {
            sdk.close()
        }
    }

    @Test
    fun anOAuthRedirectReachesAWaitingConnectFlow() = runTest {
        val sdk = start()
        try {
            val received = mutableListOf<OAuthCallbackParams>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                sdk.oauthCallbacks.collect { received += it }
            }

            assertTrue(sdk.handleOAuthRedirect("keryx://oauth2/callback?code=abc&state=xyz"))
            assertEquals(listOf("abc"), received.map { it.code })
            assertEquals("xyz", received.single().state)
            collector.cancel()
        } finally {
            sdk.close()
        }
    }

    @Test
    fun theSharedControllersAreReachableAndStartInTheirLocalOnlyState() = runTest {
        val sdk = start()
        try {
            assertNull(sdk.cloudSyncController.connectedType.value)
            assertEquals(sdk.availableCloudTypes, sdk.setupController.availableCloudTypes)
            assertTrue(sdk.preferences.localSettings.value.notificationEnabled)
            assertNull(sdk.notificationAlerts.alertToSurface.value)
        } finally {
            sdk.close()
        }
    }

    @Test
    fun opmlExportsAndImportsTheSameEmptyDocumentThroughTheSdk() = runTest {
        val sdk = start()
        try {
            val exported = sdk.opml.exportOpml()
            val outcome = sdk.opml.importOpml(exported)
            assertEquals(0, outcome.added)
            assertEquals(0, outcome.failed)
        } finally {
            sdk.close()
        }
    }

    @Test
    fun menuStateGatesEverythingOnHome() = runTest {
        val sdk = start()
        try {
            val onHome = sdk.menuState(
                onHome = true,
                hasSelectedArticle = false,
                selectedArticleHasUrl = false,
                canSyncNow = false,
                searchActive = false,
                unreadOnly = false,
            )
            assertTrue(onHome.addItemsEnabled)

            val awayFromHome = sdk.menuState(
                onHome = false,
                hasSelectedArticle = false,
                selectedArticleHasUrl = false,
                canSyncNow = false,
                searchActive = false,
                unreadOnly = false,
            )
            assertFalse(awayFromHome.addItemsEnabled)

            // "Sync now" follows the shared ManualSync predicate it is handed, on Home only.
            fun syncEnabled(onHome: Boolean, canSyncNow: Boolean) = sdk.menuState(
                onHome = onHome,
                hasSelectedArticle = false,
                selectedArticleHasUrl = false,
                canSyncNow = canSyncNow,
                searchActive = false,
                unreadOnly = false,
            ).syncEnabled
            assertTrue(syncEnabled(onHome = true, canSyncNow = true))
            assertFalse(syncEnabled(onHome = true, canSyncNow = false))
            assertFalse(syncEnabled(onHome = false, canSyncNow = true))
        } finally {
            sdk.close()
        }
    }

    @Test
    fun importOpenedOpmlPostsAnInfoNotificationForAnEmptyDocument() = runTest {
        val sdk = start()
        try {
            sdk.importOpenedOpml("<opml><body></body></opml>")

            assertEquals(1, sdk.notificationCenter.items.value.size)
        } finally {
            sdk.close()
        }
    }

    @Test
    fun startMaintenanceIsIdempotent() = runTest {
        val sdk = start()
        try {
            // A fresh dataDirectory has no completed setup, so runStartupMaintenance's own gate
            // makes both calls near-instant no-ops; this only asserts calling it twice doesn't
            // start a second overlapping loop (which close() below would then fail to join cleanly).
            sdk.startMaintenance()
            sdk.startMaintenance()
        } finally {
            sdk.close()
        }
    }
}
