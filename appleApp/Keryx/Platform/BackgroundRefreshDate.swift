import Foundation

/// The earliest moment the OS may start the next background refresh: `minutes` from `now`. The OS
/// treats it as a lower bound only — when it actually runs is its own decision.
func backgroundRefreshEarliestBeginDate(minutes: Int64, now: Date = Date()) -> Date {
    now.addingTimeInterval(TimeInterval(max(minutes, 0)) * 60)
}
