#if os(macOS)
import AppKit
import KeryxShared
import ObjectiveC
import SwiftUI
import Testing
import UniformTypeIdentifiers

/// Checks, against the SwiftUI these tests run on, every assumption `SidebarDropGuard` makes about
/// SwiftUI's internals — so a new SDK or macOS that changes them fails CI instead of silently turning
/// the sidebar's drag and drop off. A `List` shaped like the sidebar is hosted in an offscreen window,
/// guarded, and then sent drops through a stand-in `NSDraggingInfo`; no real mouse drag is involved.
@MainActor
@Suite(.serialized)
struct SidebarDropGuardTests {
    /// The sidebar's sections and rows, without the app around them: All/Starred, two folders with a
    /// feed each, one unfoldered feed, and a tag with a copy of a feed under it.
    private struct GuardedSidebar: View {
        let recorder: DropRecorder

        @State private var selection: String?

        var body: some View {
            let expanded = recorder.expanded
            List(selection: $selection) {
                Section { Text("All") }
                Section("Folders", isExpanded: .constant(true)) {
                    ForEach(["d1", "d2"], id: \.self) { folder in
                        DisclosureGroup(isExpanded: isExpanded(folder, in: expanded)) {
                            ForEach(["\(folder)-f"], id: \.self) { Text($0) }
                        } label: {
                            Text(folder)
                        }
                    }
                }
                Section {
                    ForEach(["loose"], id: \.self) { Text($0) }
                } header: {
                    Text("No folder")
                }
                Section("Tags", isExpanded: .constant(true)) {
                    ForEach(["t1"], id: \.self) { tag in
                        DisclosureGroup(isExpanded: isExpanded(tag, in: expanded)) {
                            ForEach(["\(tag)/loose"], id: \.self) { Text($0) }
                        } label: {
                            Text(tag)
                        }
                    }
                }
            }
            .listStyle(.sidebar)
            .background(SidebarDropGuard(
                folderCount: 2,
                tagCount: 1,
                resolve: { recorder.actions[$0] },
                apply: { recorder.applied.append($0) }
            ))
        }

        private func isExpanded(_ row: String, in expanded: Set<String>) -> Binding<Bool> {
            Binding(
                get: { expanded.contains(row) },
                set: { if $0 { recorder.expanded.insert(row) } else { recorder.expanded.remove(row) } }
            )
        }
    }

    @MainActor
    @Observable
    final class DropRecorder {
        /// What each destination resolves to; a destination missing here resolves to nothing.
        @ObservationIgnored var actions: [SidebarDropDestination: FeedListDropAction] = [:]
        @ObservationIgnored var applied: [FeedListDropAction] = []
        /// The folders and tags the sidebar's own state shows expanded.
        var expanded: Set<String> = ["d1", "d2", "t1"]
    }

    private let recorder = DropRecorder()
    private let window: NSWindow

    init() {
        _ = NSApplication.shared
        window = NSWindow(
            contentRect: NSRect(x: -10_000, y: -10_000, width: 300, height: 600),
            styleMask: [.titled], backing: .buffered, defer: false
        )
        window.isReleasedWhenClosed = false
        window.contentView = NSHostingView(rootView: GuardedSidebar(recorder: recorder))
        window.orderFrontRegardless()
    }

    /// The guarded outline, once SwiftUI has built it and the guard has had its turn.
    private func guardedOutline() throws -> NSOutlineView {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            if let outline = Self.firstOutline(in: window.contentView), SidebarDropGuardInstaller.isGuarded(outline) {
                return outline
            }
            RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        }
        let outline = Self.firstOutline(in: window.contentView)
        throw OutlineNotGuarded(
            outline: outline.flatMap { object_getClass($0) }.map(NSStringFromClass),
            dataSource: (outline?.dataSource as? NSObject).flatMap { object_getClass($0) }.map(NSStringFromClass),
            inWindow: outline?.window != nil
        )
    }

    private struct OutlineNotGuarded: Error {
        let outline: String?
        let dataSource: String?
        let inWindow: Bool
    }

    private static func firstOutline(in view: NSView?) -> NSOutlineView? {
        guard let view else { return nil }
        if let outline = view as? NSOutlineView { return outline }
        return view.subviews.lazy.compactMap { firstOutline(in: $0) }.first
    }

    // MARK: - SwiftUI's internals the guard relies on

    @Test
    func theListIsAnOutlineWhoseDataSourceValidatesDrops() throws {
        let outline = try guardedOutline()
        let dataSource = try #require(outline.dataSource as? NSObject)
        #expect(dataSource.responds(to: #selector(NSOutlineViewDataSource.outlineView(_:validateDrop:proposedItem:proposedChildIndex:))))
    }

    @Test
    func theListsSectionsAreTheOutlinesRootItems() throws {
        let outline = try guardedOutline()
        #expect(outline.numberOfChildren(ofItem: nil) == SidebarRootSection.roots(folderCount: 2, tagCount: 1).count)
        let reader = SidebarDropOutline(outline, folderCount: 2, tagCount: 1)
        #expect(reader.foldersSection != nil)
        #expect(reader.noFolderSection != nil)
        #expect(reader.tagsSection != nil)
    }

    @Test
    func everyRowIsReadAsTheRowItIs() throws {
        let outline = try guardedOutline()
        let reader = SidebarDropOutline(outline, folderCount: 2, tagCount: 1)
        let folders = try #require(reader.foldersSection)
        let noFolder = try #require(reader.noFolderSection)
        let tags = try #require(reader.tagsSection)
        let secondFolder = outline.child(1, ofItem: folders)
        let tag = outline.child(0, ofItem: tags)
        #expect(reader.classify(folders) == .foldersSection)
        #expect(reader.classify(secondFolder) == .folder(index: 1))
        #expect(reader.classify(outline.child(0, ofItem: secondFolder)) == .feedInFolder(folderIndex: 1))
        #expect(reader.classify(noFolder) == .noFolderSection)
        #expect(reader.classify(outline.child(0, ofItem: noFolder)) == .unfolderedFeed)
        #expect(reader.classify(tag) == .tag(index: 0))
        #expect(reader.classify(outline.child(0, ofItem: tag)) == .feedInTag(tagIndex: 0))
    }

    // MARK: - Drops

    @Test
    func theGuardWorksWhileKVOObservesTheOutline() throws {
        let outline = try guardedOutline()
        let observer = NSObject()
        outline.addObserver(observer, forKeyPath: "toolTip", context: nil)
        defer { outline.removeObserver(observer, forKeyPath: "toolTip") }
        let folders = try #require(SidebarDropOutline(outline, folderCount: 2, tagCount: 1).foldersSection)
        let reorder = FeedListDropActionReorderFolder(draggedFolderId: "d1", targetFolderId: nil)
        recorder.actions = [.folderSlot(index: 2): reorder]
        let info = StandInDraggingInfo(carrying: feedListFolderDragType, over: outline)

        #expect(validate(outline, info, proposing: outline.child(1, ofItem: folders), at: 0) == .move)
        #expect(outline.performDragOperation(info))
        #expect(applied([reorder]))
    }

    @Test
    func theSidebarsDragTypesAreRegistered() throws {
        let types = try guardedOutline().registeredDraggedTypes
        #expect(types.contains(NSPasteboard.PasteboardType(feedListFeedDragType.identifier)))
        #expect(types.contains(NSPasteboard.PasteboardType(feedListFolderDragType.identifier)))
    }

    @Test
    func aFolderDropIsDecidedAndAppliedByTheGuard() throws {
        let outline = try guardedOutline()
        let folders = try #require(SidebarDropOutline(outline, folderCount: 2, tagCount: 1).foldersSection)
        let reorder = FeedListDropActionReorderFolder(draggedFolderId: "d2", targetFolderId: "d2")
        recorder.actions = [.folderSlot(index: 1): reorder]
        let info = StandInDraggingInfo(carrying: feedListFolderDragType, over: outline)

        // Onto the first folder: the slot just after it.
        let operation = validate(outline, info, proposing: outline.child(0, ofItem: folders), at: sidebarDropOntoRowIndex)
        #expect(operation == .move)
        #expect(outline.performDragOperation(info))
        #expect(applied([reorder]))
    }

    @Test
    func aFeedProposedIntoAFeedRowIsRefused() throws {
        let outline = try guardedOutline()
        let noFolder = try #require(SidebarDropOutline(outline, folderCount: 2, tagCount: 1).noFolderSection)
        let move = FeedListDropActionMoveFeed(feedId: "f", folderId: nil, targetFeedId: nil)
        recorder.actions = [.intoNoFolder(offset: 0): move, .ontoNoFolder: move]
        let info = StandInDraggingInfo(carrying: feedListFeedDragType, over: outline)

        let operation = validate(outline, info, proposing: outline.child(0, ofItem: noFolder), at: 0)
        #expect(operation == [])
        #expect(!outline.performDragOperation(info))
        #expect(recorder.applied.isEmpty)
    }

    @Test
    func aFeedOverACopyUnderATagGoesToThatTag() throws {
        let outline = try guardedOutline()
        let tags = try #require(SidebarDropOutline(outline, folderCount: 2, tagCount: 1).tagsSection)
        let attach = FeedListDropActionAttachTag(feedId: "f", tagId: "t1")
        recorder.actions = [.ontoFeedInTag(tagIndex: 0): attach]
        let info = StandInDraggingInfo(carrying: feedListFeedDragType, over: outline)

        let tag = outline.child(0, ofItem: tags)
        #expect(validate(outline, info, proposing: outline.child(0, ofItem: tag), at: sidebarDropOntoRowIndex) == .move)
        #expect(outline.performDragOperation(info))
        #expect(applied([attach]))
    }

    @Test
    func aDropTheSharedRulesRefuseIsRefused() throws {
        let outline = try guardedOutline()
        let folders = try #require(SidebarDropOutline(outline, folderCount: 2, tagCount: 1).foldersSection)
        recorder.actions = [:]
        let info = StandInDraggingInfo(carrying: feedListFeedDragType, over: outline)

        #expect(validate(outline, info, proposing: outline.child(0, ofItem: folders), at: sidebarDropOntoRowIndex) == [])
    }

    @Test
    func aDragFromAnotherAppIsRefused() throws {
        let outline = try guardedOutline()
        let folders = try #require(SidebarDropOutline(outline, folderCount: 2, tagCount: 1).foldersSection)
        recorder.actions = [.ontoFolder(index: 0): FeedListDropActionMoveFeed(feedId: "f", folderId: "d1", targetFeedId: nil)]
        let info = StandInDraggingInfo(carrying: feedListFeedDragType, over: outline, fromAnotherApp: true)

        #expect(validate(outline, info, proposing: outline.child(0, ofItem: folders), at: sidebarDropOntoRowIndex) == [])
        #expect(!outline.performDragOperation(info))
        #expect(recorder.applied.isEmpty)
    }

    @Test
    func aDropWhoseDestinationChangedUnderTheDragIsRefused() throws {
        let outline = try guardedOutline()
        let folders = try #require(SidebarDropOutline(outline, folderCount: 2, tagCount: 1).foldersSection)
        recorder.actions = [.ontoFolder(index: 0): FeedListDropActionMoveFeed(feedId: "f", folderId: "d1", targetFeedId: nil)]
        let info = StandInDraggingInfo(carrying: feedListFeedDragType, over: outline)
        #expect(validate(outline, info, proposing: outline.child(0, ofItem: folders), at: sidebarDropOntoRowIndex) == .move)

        // A sync reorders the folders before the drop lands: the first folder is now another one.
        recorder.actions = [.ontoFolder(index: 0): FeedListDropActionMoveFeed(feedId: "f", folderId: "d2", targetFeedId: nil)]
        #expect(!outline.performDragOperation(info))
        #expect(recorder.applied.isEmpty)
    }

    // MARK: - The drag's own state

    @Test
    func theDragEndsWhenItLeavesTheOutlineOrDrops() throws {
        let outline = try guardedOutline()
        let handler = try #require(SidebarDropGuardInstaller.handler(of: outline))
        let folders = try #require(SidebarDropOutline(outline, folderCount: 2, tagCount: 1).foldersSection)
        recorder.actions = [.folderSlot(index: 1): FeedListDropActionReorderFolder(draggedFolderId: "d2", targetFolderId: "d2")]
        let info = StandInDraggingInfo(carrying: feedListFolderDragType, over: outline)

        _ = validate(outline, info, proposing: outline.child(0, ofItem: folders), at: sidebarDropOntoRowIndex)
        #expect(handler.draggedKind == .folder)
        outline.draggingExited(info)
        #expect(handler.draggedKind == nil)
        #expect(handler.pending == nil)

        _ = validate(outline, info, proposing: outline.child(0, ofItem: folders), at: sidebarDropOntoRowIndex)
        _ = outline.performDragOperation(info)
        #expect(handler.draggedKind == nil)
        #expect(handler.pending == nil)
    }

    // MARK: - Spring-loading

    @Test
    func aDraggedFolderSpringsNothingOpen() throws {
        let outline = try guardedOutline()
        let (folder, tag) = try collapsedFolderAndTag(in: outline)
        let info = StandInDraggingInfo(carrying: feedListFolderDragType, over: outline)
        _ = validate(outline, info, proposing: outline.child(1, ofItem: nil), at: 0)

        outline.expandItem(folder)
        outline.expandItem(tag)
        #expect(!outline.isItemExpanded(folder))
        #expect(!outline.isItemExpanded(tag))
        outline.draggingExited(info)
    }

    @Test
    func aDraggedFeedSpringsOnlyAFolderOpen() throws {
        let outline = try guardedOutline()
        let (folder, tag) = try collapsedFolderAndTag(in: outline)
        let info = StandInDraggingInfo(carrying: feedListFeedDragType, over: outline)
        _ = validate(outline, info, proposing: folder, at: sidebarDropOntoRowIndex)

        outline.expandItem(folder)
        outline.expandItem(tag)
        spinUntil { outline.isItemExpanded(folder) }
        #expect(outline.isItemExpanded(folder))
        #expect(!outline.isItemExpanded(tag))
        outline.draggingExited(info)
    }

    @Test
    func aRefusedSpringLeavesTheSidebarsStateAsItWas() throws {
        let outline = try guardedOutline()
        let (_, tag) = try collapsedFolderAndTag(in: outline)
        let info = StandInDraggingInfo(carrying: feedListFeedDragType, over: outline)
        _ = validate(outline, info, proposing: tag, at: sidebarDropOntoRowIndex)

        outline.expandItem(tag)
        #expect(!recorder.expanded.contains("t1"))
        outline.draggingExited(info)
    }

    @Test
    func anExpansionTheSidebarsStateAsksForDuringADragStillHappens() throws {
        let outline = try guardedOutline()
        let (folder, _) = try collapsedFolderAndTag(in: outline)
        let info = StandInDraggingInfo(carrying: feedListFolderDragType, over: outline)
        _ = validate(outline, info, proposing: outline.child(1, ofItem: nil), at: 0)

        // A sync opens the folder in the sidebar's state while a folder is being dragged.
        recorder.expanded.insert("d1")
        spinUntil { outline.isItemExpanded(folder) }
        #expect(outline.isItemExpanded(folder))
        outline.draggingExited(info)
    }

    /// The first folder and the tag, both collapsed in the outline.
    private func collapsedFolderAndTag(in outline: NSOutlineView) throws -> (folder: Any, tag: Any) {
        // Read the rows afresh after collapsing them: SwiftUI may hand the outline new items for them.
        func rows() -> (folder: Any?, tag: Any?) {
            let reader = SidebarDropOutline(outline, folderCount: 2, tagCount: 1)
            return (reader.foldersSection.map { outline.child(0, ofItem: $0) } ?? nil,
                    reader.tagsSection.map { outline.child(0, ofItem: $0) } ?? nil)
        }
        func collapsed() -> Bool {
            let (folder, tag) = rows()
            return folder.map { !outline.isItemExpanded($0) } == true && tag.map { !outline.isItemExpanded($0) } == true
        }
        recorder.expanded.subtract(["d1", "t1"])
        spinUntil(collapsed)
        try #require(collapsed())
        let (folder, tag) = rows()
        return (try #require(folder), try #require(tag))
    }

    /// Runs the main run loop until `done` holds, for half a second at most.
    private func spinUntil(_ done: () -> Bool) {
        let deadline = Date().addingTimeInterval(0.5)
        while !done(), Date() < deadline {
            RunLoop.main.run(until: Date().addingTimeInterval(0.02))
        }
    }

    private func validate(_ outline: NSOutlineView, _ info: NSDraggingInfo, proposing item: Any?, at childIndex: Int) -> NSDragOperation {
        outline.dataSource?.outlineView?(outline, validateDrop: info, proposedItem: item, proposedChildIndex: childIndex) ?? []
    }

    /// Whether exactly `expected` was applied, in order (Kotlin values compare with `isEqual`).
    private func applied(_ expected: [FeedListDropAction]) -> Bool {
        recorder.applied.count == expected.count
            && zip(recorder.applied, expected).allSatisfy { ($0 as AnyObject).isEqual($1 as AnyObject) }
    }
}

/// A drag carrying one sidebar item, for calling the outline's drop methods directly.
@MainActor
private final class StandInDraggingInfo: NSObject, @preconcurrency NSDraggingInfo {
    let draggingPasteboard: NSPasteboard
    private let destination: NSView
    private let source: AnyObject?

    /// - Parameter fromAnotherApp: Whether the drag comes from another application, which AppKit
    ///   gives no source; otherwise the outline itself is the source, as for the sidebar's own rows.
    init(carrying type: UTType, over destination: NSView, fromAnotherApp: Bool = false) {
        draggingPasteboard = NSPasteboard.withUniqueName()
        let pasteboardType = NSPasteboard.PasteboardType(type.identifier)
        draggingPasteboard.declareTypes([pasteboardType], owner: nil)
        draggingPasteboard.setData(Data("id".utf8), forType: pasteboardType)
        self.destination = destination
        source = fromAnotherApp ? nil : destination
    }

    var draggingDestinationWindow: NSWindow? { destination.window }
    var draggingSourceOperationMask: NSDragOperation { .every }
    var draggingLocation: NSPoint { .zero }
    var draggedImageLocation: NSPoint { .zero }
    var draggedImage: NSImage? { nil }
    var draggingSource: Any? { source }
    var draggingSequenceNumber: Int { 1 }
    func slideDraggedImage(to screenPoint: NSPoint) {}
    var draggingFormation: NSDraggingFormation = .default
    var animatesToDestination = false
    var numberOfValidItemsForDrop = 1
    func enumerateDraggingItems(
        options enumOpts: NSDraggingItemEnumerationOptions = [],
        for view: NSView?,
        classes classArray: [AnyClass],
        searchOptions: [NSPasteboard.ReadingOptionKey: Any] = [:],
        using block: (NSDraggingItem, Int, UnsafeMutablePointer<ObjCBool>) -> Void
    ) {}
    var springLoadingHighlight: NSSpringLoadingHighlight { .none }
    func resetSpringLoading() {}
}
#endif
