#if os(macOS)
import AppKit
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
            List(selection: $selection) {
                Section { Text("All") }
                Section("Folders", isExpanded: .constant(true)) {
                    ForEach(["d1", "d2"], id: \.self) { folder in
                        DisclosureGroup(isExpanded: .constant(true)) {
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
                        DisclosureGroup(isExpanded: .constant(true)) {
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
                canDrop: { recorder.allowed.contains($0) },
                drop: { recorder.dropped.append($0) }
            ))
        }
    }

    @MainActor
    private final class DropRecorder {
        var allowed: [SidebarDropDestination] = []
        var dropped: [SidebarDropDestination] = []
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
        #expect(outline.numberOfChildren(ofItem: nil) == 4)
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

    // MARK: - The guard

    @Test
    func theGuardWorksWhileKVOObservesTheOutline() throws {
        let outline = try guardedOutline()
        let observer = NSObject()
        outline.addObserver(observer, forKeyPath: "toolTip", context: nil)
        defer { outline.removeObserver(observer, forKeyPath: "toolTip") }
        let folders = try #require(SidebarDropOutline(outline, folderCount: 2, tagCount: 1).foldersSection)
        recorder.allowed = [.folderSlot(index: 2)]
        let info = StandInDraggingInfo(carrying: feedListFolderDragType, over: outline)

        #expect(validate(outline, info, proposing: outline.child(1, ofItem: folders), at: 0) == .move)
        #expect(outline.performDragOperation(info))
        #expect(recorder.dropped == [.folderSlot(index: 2)])
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
        recorder.allowed = [.folderSlot(index: 1)]
        let info = StandInDraggingInfo(carrying: feedListFolderDragType, over: outline)

        // Onto the first folder: the slot just after it.
        let operation = validate(outline, info, proposing: outline.child(0, ofItem: folders), at: sidebarDropOntoRowIndex)
        #expect(operation == .move)
        #expect(outline.performDragOperation(info))
        #expect(recorder.dropped == [.folderSlot(index: 1)])
    }

    @Test
    func aFeedProposedIntoAFeedRowIsRefused() throws {
        let outline = try guardedOutline()
        let noFolder = try #require(SidebarDropOutline(outline, folderCount: 2, tagCount: 1).noFolderSection)
        recorder.allowed = [.intoNoFolder(offset: 0), .ontoNoFolder]
        let info = StandInDraggingInfo(carrying: feedListFeedDragType, over: outline)

        let operation = validate(outline, info, proposing: outline.child(0, ofItem: noFolder), at: 0)
        #expect(operation == [])
        #expect(!outline.performDragOperation(info))
        #expect(recorder.dropped.isEmpty)
    }

    @Test
    func aFeedOverACopyUnderATagGoesToThatTag() throws {
        let outline = try guardedOutline()
        let tags = try #require(SidebarDropOutline(outline, folderCount: 2, tagCount: 1).tagsSection)
        recorder.allowed = [.ontoTag(index: 0)]
        let info = StandInDraggingInfo(carrying: feedListFeedDragType, over: outline)

        let copy = outline.child(0, ofItem: outline.child(0, ofItem: tags))
        #expect(validate(outline, info, proposing: copy, at: sidebarDropOntoRowIndex) == .move)
        #expect(outline.performDragOperation(info))
        #expect(recorder.dropped == [.ontoTag(index: 0)])
    }

    @Test
    func aDropTheSharedRulesRefuseIsRefused() throws {
        let outline = try guardedOutline()
        let folders = try #require(SidebarDropOutline(outline, folderCount: 2, tagCount: 1).foldersSection)
        recorder.allowed = []
        let info = StandInDraggingInfo(carrying: feedListFeedDragType, over: outline)

        #expect(validate(outline, info, proposing: outline.child(0, ofItem: folders), at: sidebarDropOntoRowIndex) == [])
    }

    private func validate(_ outline: NSOutlineView, _ info: NSDraggingInfo, proposing item: Any?, at childIndex: Int) -> NSDragOperation {
        outline.dataSource?.outlineView?(outline, validateDrop: info, proposedItem: item, proposedChildIndex: childIndex) ?? []
    }
}

/// A drag carrying one sidebar item, for calling the outline's drop methods directly.
@MainActor
private final class StandInDraggingInfo: NSObject, @preconcurrency NSDraggingInfo {
    let draggingPasteboard: NSPasteboard
    private let destination: NSView

    init(carrying type: UTType, over destination: NSView) {
        draggingPasteboard = NSPasteboard.withUniqueName()
        let pasteboardType = NSPasteboard.PasteboardType(type.identifier)
        draggingPasteboard.declareTypes([pasteboardType], owner: nil)
        draggingPasteboard.setData(Data("id".utf8), forType: pasteboardType)
        self.destination = destination
    }

    var draggingDestinationWindow: NSWindow? { destination.window }
    var draggingSourceOperationMask: NSDragOperation { .every }
    var draggingLocation: NSPoint { .zero }
    var draggedImageLocation: NSPoint { .zero }
    var draggedImage: NSImage? { nil }
    var draggingSource: Any? { nil }
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
