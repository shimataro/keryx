package works.merc.keryx.app.ui.home

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import app.cash.sqldelight.db.SqlDriver
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.presentation.home.HomeViewModelFixture
import works.merc.keryx.app.presentation.home.HomeViewModelFixtureTokenStorage
import works.merc.keryx.app.presentation.home.newHomeViewModel
import works.merc.keryx.app.presentation.settings.ManualSync

/**
 * Builds a [HomeViewModel] over [driver]/[db], runs [block] against it, then tears the whole
 * fixture down — see [HomeViewModelFixture.close] for the order and why it matters.
 *
 * This is the only supported way to get a [HomeViewModel] in a Compose UI test: teardown cannot be
 * forgotten, cannot be placed outside the Compose test (where [ComposeUiTest.waitForIdle] isn't
 * available), and cannot be ordered wrongly.
 */
@OptIn(ExperimentalTestApi::class)
internal suspend fun <T> ComposeUiTest.useHomeViewModel(
    driver: SqlDriver,
    db: KeryxDatabase,
    syncScheduler: SyncScheduler = SyncScheduler {},
    clock: Clock = Clock { 0L },
    activityCenter: ActivityCenter = ActivityCenter(),
    tokenStorage: TokenStorage = HomeViewModelFixtureTokenStorage(),
    appKey: String = "",
    // See newHomeViewModel's own parameter: null builds the real CloudSyncController.
    manualSync: ManualSync? = null,
    block: suspend (HomeViewModelFixture) -> T,
): T {
    val fixture = newHomeViewModel(driver, db, syncScheduler, clock, activityCenter, tokenStorage, appKey, manualSync = manualSync)
    return try {
        // waitForIdle only on the success path: it rethrows Compose's own uncaught exceptions, and
        // doing that from a `finally` would mask the assertion failure that actually ended [block].
        block(fixture).also { waitForIdle() }
    } finally {
        fixture.close()
    }
}
