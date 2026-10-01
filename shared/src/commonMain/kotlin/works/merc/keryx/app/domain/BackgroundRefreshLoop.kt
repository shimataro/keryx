package works.merc.keryx.app.domain

import kotlinx.coroutines.delay
import org.koin.core.Koin
import works.merc.keryx.app.core.MILLIS_PER_MINUTE
import works.merc.keryx.app.core.SystemClock

/**
 * Runs periodic background maintenance tasks in an infinite loop — the desktop app's own
 * scheduling mechanism, also used by the Apple app (via `KeryxSdk.startMaintenance()`; see
 * "Apple Native Apps (SwiftUI)" in `docs/app-architecture.md`), since both keep one long-lived
 * process to run a coroutine in. Android instead schedules each step through `WorkManager` (a
 * fundamentally different, process-independent mechanism), so it has no equivalent of this loop.
 *
 * Feed refreshing and synchronization occur when the configured refresh interval is positive.
 * Update checks and full-text index maintenance run independently of feed refresh settings. Each
 * step runs through [runMaintenanceStep] — a failure in one (e.g. a feed fetch timing out) must
 * not skip the sync/[checkForUpdateAndNotify]/[maybeRebuildFtsIndex] for the rest of this cycle.
 */
suspend fun backgroundUpdateLoop(koin: Koin) {
    val settingsRepository = koin.get<SettingsRepository>()
    while (true) {
        val minutes = settingsRepository.getLocalSettings().refreshIntervalMinutes
        delay(if (minutes <= 0) MILLIS_PER_MINUTE else minutes * MILLIS_PER_MINUTE)
        // See runStartupMaintenance's own comment: nothing here should touch local_settings.json
        // before setup completes.
        if (!settingsRepository.isSetupComplete()) continue
        if (minutes > 0) {
            // One RefreshCycleRunner cycle (refresh, notify, then sync if connected), so the gap
            // between the two stages isn't mistaken for idle; each stage still isolates its own
            // failure through `step`.
            runMaintenanceStep("refreshCycle") {
                koin.get<RefreshCycleRunner>().run(trigger = SyncTrigger.AUTOMATIC, step = ::runMaintenanceStep)
            }
        }
        val settings = settingsRepository.getLocalSettings()
        if (shouldCheckForUpdate(SystemClock.nowMillis(), settings.lastUpdateCheckAt, settings.updateCheckIntervalHours)) {
            runMaintenanceStep("updateCheck") { checkForUpdateAndNotify(koin) }
        }
        runMaintenanceStep("ftsRebuild") { maybeRebuildFtsIndex(koin) }
    }
}
