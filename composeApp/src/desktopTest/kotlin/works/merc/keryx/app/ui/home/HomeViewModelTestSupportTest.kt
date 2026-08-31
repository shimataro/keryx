package works.merc.keryx.app.ui.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import works.merc.keryx.app.inMemoryDb
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Pins the invariant [ComposeUiTest.useHomeViewModel] exists to guarantee: once its block returns
 * and teardown runs, [HomeViewModel.viewModelScope] is no longer active. A regression here means
 * the `SharingStarted.Eagerly` DB collectors it started can outlive the test and throw against the
 * driver a *later* test just closed — surfacing there, flakily, as
 * `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`. See [HomeViewModelFixture.close]'s KDoc
 * for the full mechanism.
 */
@OptIn(ExperimentalTestApi::class)
class HomeViewModelTestSupportTest {

    @Test
    fun useHomeViewModelLeavesNoLiveViewModelScopeBehind() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        val vm = useHomeViewModel(driver, db) { it.vm }
        assertFalse(vm.viewModelScope.isActive)
    }

    /**
     * Pins the sibling invariant for the default [ActivityCenter] [newHomeViewModel]/
     * [ComposeUiTest.useHomeViewModel] build when no [ActivityCenter] is supplied: its own scope
     * (holding the `SharingStarted.Eagerly` `feedRefreshing`/`syncing` collectors) must be
     * cancelled by [HomeViewModelFixture.close] too, not just [HomeViewModel.viewModelScope]. A
     * regression here leaks one live coroutine scope per test that doesn't pass its own
     * [ActivityCenter].
     */
    @Test
    fun useHomeViewModelCancelsOwnedActivityCenterScope() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        val ownedScope = useHomeViewModel(driver, db) { it.ownedActivityCenterScope }
        assertFalse(ownedScope!!.isActive)
    }

    /**
     * Pins the failure-path counterpart of [useHomeViewModelCancelsOwnedActivityCenterScope]:
     * [newHomeViewModel] never gets to return a [HomeViewModelFixture] here, so
     * [HomeViewModelFixture.close] is never reachable — the owned [ActivityCenter] scope's eager
     * `SharingStarted.Eagerly` collectors must instead be cancelled by [newHomeViewModel]'s own
     * failure cleanup. A regression here leaks one live coroutine scope per construction that fails
     * partway through.
     */
    @Test
    fun newHomeViewModelCancelsOwnedResourcesWhenLateConstructionFails() {
        val (driver, db) = inMemoryDb()
        var capturedScope: CoroutineScope? = null
        try {
            assertFailsWith<IllegalStateException> {
                newHomeViewModel(driver, db, injectFailureAfterActivityCenter = { scope ->
                    capturedScope = scope
                    error("simulated late construction failure")
                })
            }
            assertFalse(capturedScope!!.isActive)
        } finally {
            // newHomeViewModel never returns a HomeViewModelFixture here, so nothing else closes
            // the caller-owned driver (matching HomeViewModelFixture.close()'s ownership model).
            driver.close()
        }
    }
}
