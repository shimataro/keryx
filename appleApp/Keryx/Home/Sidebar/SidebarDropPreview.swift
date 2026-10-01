import KeryxShared

/// The sidebar's structure as a drop will leave it, worked out ahead of the shared state that
/// carries the same result.
///
/// UIKit expects the data source to already show the result when `performDropWith` returns; the
/// view model's new state only arrives afterwards, so without this the dropped row lands in its gap,
/// the gap closes, and the row then moves in a second animation. The rules follow the repositories'
/// (`reorderIds`): the moved item is taken out, then goes before its target — or last when there is
/// no target in that group.
enum SidebarDropPreview {
    /// The outline after `action`, or `nil` when it changes no structure the outline shows (tagging
    /// a feed) or names a row that isn't there.
    static func outline(after action: FeedListDropAction, in outline: SidebarOutline) -> SidebarOutline? {
        switch onEnum(of: action) {
        case .attachTag:
            return nil
        case .moveFeed(let move):
            return movingFeed(move.feedId, into: move.folderId, before: move.targetFeedId, in: outline)
        case .reorderFolder(let reorder):
            return reorderingFolder(reorder.draggedFolderId, before: reorder.targetFolderId, in: outline)
        }
    }

    private static func movingFeed(_ feedId: String, into folderId: String?, before targetId: String?, in outline: SidebarOutline) -> SidebarOutline? {
        guard feedId != targetId else { return nil }
        let feed = SidebarItemID.feed(feedId)
        let target = targetId.map(SidebarItemID.feed)
        var moved: SidebarOutline.Node?
        var sections = outline.sections.map { section in
            switch section.section {
            case .folders:
                return section.mapping { header in
                    var header = header
                    header.children = header.children.map { folder in
                        var folder = folder
                        if let index = folder.children.firstIndex(where: { $0.id == feed }) {
                            moved = folder.children.remove(at: index)
                        }
                        return folder
                    }
                    return header
                }
            case .noFolder:
                var nodes = section.nodes
                if let index = nodes.firstIndex(where: { $0.id == feed }) {
                    moved = nodes.remove(at: index)
                }
                return section.replacingNodes(nodes)
            default:
                return section
            }
        }
        guard let moved else { return nil }

        var placed = false
        sections = sections.map { section in
            if let folderId, section.section == .folders {
                return section.mapping { header in
                    var header = header
                    header.children = header.children.map { folder in
                        guard folder.id == .folder(folderId) else { return folder }
                        var folder = folder
                        insert(moved, into: &folder.children, before: target)
                        placed = true
                        return folder
                    }
                    return header
                }
            }
            if folderId == nil, section.section == .noFolder {
                var nodes = section.nodes
                insert(moved, into: &nodes, before: target)
                placed = true
                return section.replacingNodes(nodes)
            }
            return section
        }
        return placed ? SidebarOutline(sections: sections) : nil
    }

    private static func reorderingFolder(_ folderId: String, before targetId: String?, in outline: SidebarOutline) -> SidebarOutline? {
        guard folderId != targetId else { return nil }
        let folder = SidebarItemID.folder(folderId)
        var found = false
        let sections = outline.sections.map { section in
            guard section.section == .folders else { return section }
            return section.mapping { header in
                var header = header
                guard let index = header.children.firstIndex(where: { $0.id == folder }) else { return header }
                let moved = header.children.remove(at: index)
                insert(moved, into: &header.children, before: targetId.map(SidebarItemID.folder))
                found = true
                return header
            }
        }
        return found ? SidebarOutline(sections: sections) : nil
    }

    private static func insert(_ node: SidebarOutline.Node, into nodes: inout [SidebarOutline.Node], before target: SidebarItemID?) {
        let index = target.flatMap { target in nodes.firstIndex { $0.id == target } } ?? nodes.count
        nodes.insert(node, at: index)
    }
}

private extension SidebarOutline.Section {
    func replacingNodes(_ nodes: [SidebarOutline.Node]) -> SidebarOutline.Section {
        SidebarOutline.Section(section: section, nodes: nodes, expanded: expanded)
    }

    /// The section with its header node (the Folders header, the only top-level node) transformed.
    func mapping(_ transform: (SidebarOutline.Node) -> SidebarOutline.Node) -> SidebarOutline.Section {
        replacingNodes(nodes.map(transform))
    }
}
