#if os(iOS)
import BackgroundTasks
import KeryxShared

/// Schedules the iOS periodic background refresh (`BGAppRefreshTask`), the counterpart of Android's
/// `WorkManager` job. The setting-to-schedule mapping is the shared `backgroundRefreshSchedule`; a
/// request is one-shot, so every run (and every move to the background) schedules the next one.
enum BackgroundRefresh {
    static let taskIdentifier = "works.merc.keryx.refresh"

    /// The shortest interval the app's own UI offers, and the floor Android's scheduler imposes.
    private static let minimumMinutes: Int64 = 15

    static func schedule(refreshIntervalMinutes: Int32) {
        let schedule = BackgroundRefreshScheduleKt.backgroundRefreshSchedule(
            refreshIntervalMinutes: refreshIntervalMinutes, minimumMinutes: minimumMinutes
        )
        switch onEnum(of: schedule) {
        case .disabled:
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskIdentifier)
        case .periodic(let periodic):
            let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
            request.earliestBeginDate = backgroundRefreshEarliestBeginDate(minutes: periodic.minutes)
            try? BGTaskScheduler.shared.submit(request)
        }
    }
}
#endif
