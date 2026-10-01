import KeryxShared

/// The iOS sidebar's structure — its sections, each a tree of items, and which items are expanded —
/// derived from `SidebarModel` and the disclosure state. The collection view applies one section
/// snapshot per `Section`; a collapsed item keeps its children in the tree (only hidden), so
/// expanding it is not a structural change.
struct SidebarOutline: Equatable, Sendable {
    struct Node: Equatable, Sendable {
        let id: SidebarItemID
        var children: [Node] = []
    }

    struct Section: Equatable, Sendable {
        let section: SidebarSection
        /// The section's top-level items: All and Starred, the "No folder" header followed by the
        /// unfoldered feeds, or the Folders / Tags header with the folders / tags under it.
        let nodes: [Node]
        /// The items in this section whose children are shown.
        let expanded: Set<SidebarItemID>

        /// The items on screen, in display order — the children of a collapsed item left out.
        var visibleItems: [SidebarItemID] {
            var items: [SidebarItemID] = []
            func visit(_ node: Node) {
                items.append(node.id)
                guard expanded.contains(node.id) else { return }
                node.children.forEach(visit)
            }
            nodes.forEach(visit)
            return items
        }

        /// The descendants of `item`, collapsed or not — empty when `item` has none or isn't here.
        func descendants(of item: SidebarItemID) -> [SidebarItemID] {
            func find(_ nodes: [Node]) -> Node? {
                for node in nodes {
                    if node.id == item { return node }
                    if let found = find(node.children) { return found }
                }
                return nil
            }
            guard let found = find(nodes) else { return [] }
            var items: [SidebarItemID] = []
            func visit(_ node: Node) {
                items.append(node.id)
                node.children.forEach(visit)
            }
            found.children.forEach(visit)
            return items
        }
    }

    let sections: [Section]

    init(sections: [Section]) {
        self.sections = sections
    }

    /// - Parameters:
    ///   - foldersExpanded: Whether the Folders section header is expanded (`FeedListView`'s own
    ///     `@AppStorage` flag), as opposed to the per-folder state in `collapsedFolderIds`.
    ///   - tagsExpanded: The same for the Tags section header.
    init(
        model: SidebarModel,
        collapsedFolderIds: Set<String>,
        expandedTagIds: Set<String>,
        foldersExpanded: Bool,
        tagsExpanded: Bool
    ) {
        var sections = [Section(section: .smart, nodes: [Node(id: .all), Node(id: .starred)], expanded: [])]

        // Like the macOS list, the Folders and Tags sections only appear when they have something
        // in them, while "No folder" is always there as a drop target.
        if !model.sortedFolders.isEmpty {
            let header = SidebarItemID.sectionHeader(.folders)
            var expanded: Set<SidebarItemID> = foldersExpanded ? [header] : []
            let folders = model.sortedFolders.map { folder in
                let id = SidebarItemID.folder(folder.id)
                if !collapsedFolderIds.contains(folder.id) { expanded.insert(id) }
                return Node(id: id, children: model.feeds(inFolder: folder.id).map { Node(id: .feed($0.id)) })
            }
            sections.append(Section(section: .folders, nodes: [Node(id: header, children: folders)], expanded: expanded))
        }

        // The unfoldered feeds are the header's siblings, not its children: the group never
        // collapses.
        sections.append(Section(
            section: .noFolder,
            nodes: [Node(id: .noFolderHeader)] + model.unassignedFeeds.map { Node(id: .feed($0.id)) },
            expanded: []
        ))

        if !model.sortedTags.isEmpty {
            let header = SidebarItemID.sectionHeader(.tags)
            var expanded: Set<SidebarItemID> = tagsExpanded ? [header] : []
            let tags = model.sortedTags.map { tag in
                let id = SidebarItemID.tag(tag.id)
                if expandedTagIds.contains(tag.id) { expanded.insert(id) }
                return Node(
                    id: id,
                    children: model.feeds(taggedWith: tag.id).map { Node(id: .feedInTag(feedId: $0.id, tagId: tag.id)) }
                )
            }
            sections.append(Section(section: .tags, nodes: [Node(id: header, children: tags)], expanded: expanded))
        }
        self.sections = sections
    }

    func section(_ section: SidebarSection) -> Section? {
        sections.first { $0.section == section }
    }

    /// Every item in the outline, hidden ones included, in depth-first order — one pass over the
    /// tree, rather than searching it again for every top-level node's descendants.
    var allItems: [SidebarItemID] {
        var items: [SidebarItemID] = []
        func visit(_ node: Node) {
            items.append(node.id)
            node.children.forEach(visit)
        }
        for section in sections {
            section.nodes.forEach(visit)
        }
        return items
    }

    /// The selectable items on screen, in display order — with both section headers expanded, the
    /// same rows as `SidebarModel.orderedRows`, which keyboard navigation walks.
    var visibleRows: [SidebarItemID] {
        sections.flatMap(\.visibleItems).filter { $0.selectionKey != nil }
    }
}
