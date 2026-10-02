import Foundation
import Testing

@Suite
struct BackgroundRefreshDateTests {
    private let now = Date(timeIntervalSince1970: 1_000_000)

    @Test
    func beginsTheGivenMinutesFromNow() {
        #expect(backgroundRefreshEarliestBeginDate(minutes: 30, now: now) == now.addingTimeInterval(1800))
    }

    @Test
    func nonPositiveMinutesBeginNow() {
        #expect(backgroundRefreshEarliestBeginDate(minutes: 0, now: now) == now)
        #expect(backgroundRefreshEarliestBeginDate(minutes: -10, now: now) == now)
    }
}
