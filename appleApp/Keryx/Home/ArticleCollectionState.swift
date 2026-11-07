import CoreGraphics
import Foundation

/// A scroll `ArticleListView` asks `ArticleCollectionView` for, carried out once per `generation`.
struct ArticleListScrollRequest: Equatable {
    enum Kind: Equatable {
        /// Bring a row into view with the least scrolling (`ArticleListScrollGate.Target`).
        case reveal(ArticleListScrollGate.Target)
        /// The new-articles pill's jump to the fresh end.
        case freshEnd(newestFirst: Bool)
    }

    var generation = 0
    var kind: Kind?
}

/// Hands out each `ArticleListScrollRequest` once: a request whose generation was already taken
/// yields nothing.
struct ScrollRequestTracker {
    private var generation: Int

    init(generation: Int) {
        self.generation = generation
    }

    /// The request's kind when its generation is new (recording that generation), else `nil`.
    mutating func take(_ request: ArticleListScrollRequest) -> ArticleListScrollRequest.Kind? {
        guard request.generation != generation else { return nil }
        generation = request.generation
        return request.kind
    }
}

/// What a row change is positioned against: the top visible row and the rows before the change, or
/// the top when `reset` is set.
struct DeferredPosition: Equatable {
    var anchor: ScrollAnchor?
    var oldIds: [String]
    var reset: Bool
}

/// The position a row change that landed while the list was off screen is put back to once the list
/// returns: the position before the first such change, with a reset asked for by any of them.
struct DeferredPositionKeeper {
    private(set) var pending: DeferredPosition?

    /// Keeps the first pending anchor and old ids; `reset` adds to the pending one.
    mutating func record(anchor: ScrollAnchor?, oldIds: [String], reset: Bool) {
        if let pending {
            self.pending = DeferredPosition(anchor: pending.anchor, oldIds: pending.oldIds, reset: pending.reset || reset)
        } else {
            pending = DeferredPosition(anchor: anchor, oldIds: oldIds, reset: reset)
        }
    }

    /// The pending position, clearing it.
    mutating func take() -> DeferredPosition? {
        defer { pending = nil }
        return pending
    }
}

/// When the row height needs measuring: once the list is on screen, and again when the content size
/// category (Dynamic Type) changes or the list has left its window.
struct RowHeightMeasurePolicy {
    private(set) var measured = false
    private var category: String?

    func needsMeasure(isActive: Bool, category: String) -> Bool {
        isActive && (!measured || self.category != category)
    }

    mutating func didMeasure(category: String) {
        measured = true
        self.category = category
    }

    mutating func windowLost() {
        measured = false
    }
}
