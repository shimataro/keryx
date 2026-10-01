import KeryxShared

/// `Folders`/`Tags`/`Feeds` already carry a stored `id: String` (SQLDelight's own primary key column)
/// — these conformances let `.sheet(item:)`/`ForEach` use them directly without a separate wrapper.
extension Folders: @retroactive Identifiable {}
extension Tags: @retroactive Identifiable {}
extension Feeds: @retroactive Identifiable {}
