import KeryxShared

/// SKIE's suspend-function overlays are not statically `Sendable`-annotated, so Swift 6's strict
/// concurrency checker flags calling one from a `Task` as "risks causing data races" even though
/// Kotlin's own coroutine machinery already serializes access correctly (the underlying dispatcher
/// confines this instance's mutable state). `@unchecked Sendable` here is the standard, documented
/// escape hatch for this exact KMP/SKIE + Swift 6 friction point — it tells the compiler what
/// Kotlin's coroutine model already guarantees, rather than working around a real data race.
extension AddFeedController: @unchecked Sendable {}
extension OpmlTransfer: @unchecked Sendable {}
extension KeryxSdk: @unchecked Sendable {}
