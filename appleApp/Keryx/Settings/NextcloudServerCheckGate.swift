/// Runs `NextcloudServerSheet`'s server check and hands its result on only while the sheet is still
/// open.
///
/// Closing the sheet calls `close()` at the moment of the close action itself: `onDisappear` only
/// runs once the sheet's dismissal has finished animating, and a check that passed in between would
/// otherwise still start the sign-in the user just cancelled. Generic over the result so it carries
/// no `KeryxShared` dependency and can be tested on its own.
@MainActor
final class NextcloudServerCheckGate<Value> {
    private var task: Task<Void, Never>?
    private(set) var isClosed = false

    /// Starts `check`, cancelling any check still running, and calls `onPassed` with its result unless
    /// the gate was closed (or the check replaced) in the meantime, or the check produced nothing.
    /// Does nothing once the gate is closed.
    func start(check: @escaping @MainActor () async -> Value?, onPassed: @escaping @MainActor (Value) -> Void) {
        guard !isClosed else { return }
        task?.cancel()
        task = Task { @MainActor [weak self] in
            let value = await check()
            guard let self, !self.isClosed, !Task.isCancelled, let value else { return }
            self.task = nil
            onPassed(value)
        }
    }

    /// Drops the running check, if any, and every result still to come. Idempotent.
    func close() {
        isClosed = true
        task?.cancel()
        task = nil
    }
}
