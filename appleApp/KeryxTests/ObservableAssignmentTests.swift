import Testing

/// Covers `ObservableAssignment.assignIfChanged` and `CoalescedAction`, the bridge layer's guards
/// against invalidating views with an unchanged value or rebuilding more than once per turn.
@Suite
@MainActor
struct ObservableAssignmentTests {
    private final class Target: ObservableAssignment {
        var writes = 0
        var value = 0 { didSet { writes += 1 } }
    }

    /// Shared mutable state for the `@MainActor` closures below.
    @MainActor
    private final class Recorder {
        var runs = 0
        var scheduled: [@MainActor () -> Void] = []
        func runScheduled() { scheduled.removeFirst()() }
    }

    @Test
    func assignsOnlyAChangedValue() {
        let target = Target()
        #expect(!target.assignIfChanged(\.value, 0))
        #expect(target.writes == 0)
        #expect(target.assignIfChanged(\.value, 1))
        #expect(target.value == 1)
        #expect(!target.assignIfChanged(\.value, 1))
        #expect(target.writes == 1)
    }

    @Test
    func coalescesMarksUntilTheScheduledRun() {
        let r = Recorder()
        let action = CoalescedAction(schedule: { r.scheduled.append($0) }, action: { r.runs += 1 })

        action.markDirty()
        action.markDirty()
        action.markDirty()
        #expect(r.scheduled.count == 1)
        #expect(r.runs == 0)
        #expect(action.isPending)

        r.runScheduled()
        #expect(r.runs == 1)
        #expect(!action.isPending)

        // A mark after the run schedules a fresh one.
        action.markDirty()
        #expect(r.scheduled.count == 1)
        r.runScheduled()
        #expect(r.runs == 2)
    }

    @Test
    func flushRunsAPendingActionOnceAndTheScheduledRunIsThenANoOp() {
        let r = Recorder()
        let action = CoalescedAction(schedule: { r.scheduled.append($0) }, action: { r.runs += 1 })

        action.flush()
        #expect(r.runs == 0)
        action.markDirty()
        action.flush()
        #expect(r.runs == 1)
        r.runScheduled()
        #expect(r.runs == 1)
    }

    @Test
    func defaultScheduleRunsOnALaterMainActorTurn() async {
        let r = Recorder()
        let action = CoalescedAction(action: { r.runs += 1 })
        action.markDirty()
        action.markDirty()
        #expect(r.runs == 0)
        for _ in 0..<10 where r.runs == 0 { await Task.yield() }
        #expect(r.runs == 1)
    }
}
