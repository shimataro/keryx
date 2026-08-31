package works.merc.keryx.app.ui.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.isActive
import works.merc.keryx.app.inMemoryDb
import kotlin.test.Test
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
}
