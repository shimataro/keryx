package works.merc.keryx.app.domain

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import works.merc.keryx.app.di.sharedModule
import kotlin.test.Test

/**
 * The Apple app installs no `updateModule` (the App Store or Sparkle updates it), yet runs the
 * shared [runStartupMaintenance] sequence, update-check step included.
 */
class StartupMaintenanceAppleTest {
    @Test
    fun theUpdateCheckIsSkippedWithoutTheUpdateModule() = runTest {
        val koin = koinApplication { modules(sharedModule()) }.koin

        // Unsupported on Apple, so this must return before resolving anything from the updater;
        // a missing gate or a failed short-circuit throws NoDefinitionFoundException here.
        checkForUpdateAndNotify(koin)
    }
}
