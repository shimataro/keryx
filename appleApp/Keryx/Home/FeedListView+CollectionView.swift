#if os(iOS)
import KeryxShared
import SwiftUI

/// What `FeedListView` hands the iOS sidebar's collection view (`SidebarCollectionView`): the
/// rendered state and the operations its rows perform — the same ones the macOS source list's rows
/// perform through their bindings and SwiftUI menus.
extension FeedListView {
    var collectionState: SidebarRenderState {
        let outline = SidebarOutline(
            model: sidebar,
            collapsedFolderIds: home.collapsedFolderIds,
            expandedTagIds: home.expandedTagIds,
            foldersExpanded: foldersExpanded,
            tagsExpanded: tagsExpanded
        )
        let displayedKey = CompactSidebarSelection.displayedKey(selectedKey: selectedRowKey, sidebarIsTopmost: sidebarIsTopmost)
        return SidebarRenderState(
            outline: outline,
            contents: SidebarRowContent.build(
                outline: outline,
                model: sidebar,
                selectionDisplayed: displayedKey != nil,
                selectedRow: home.selectedRowInstance,
                filter: home.filter,
                renamingRowKey: dialogs.renamingRowKey
            ),
            selectedItem: displayedKey == nil ? nil : SidebarItemID(home.selectedRowInstance),
            renamingKey: dialogs.renamingRowKey
        )
    }

    var collectionActions: SidebarCollectionActions {
        SidebarCollectionActions(
            select: selectRow,
            setExpanded: setExpanded,
            menu: { SidebarContextMenus.menu(for: $0, home: home, dialogs: dialogs) },
            editor: renameEditor(for:),
            dropIndex: { dropIndex },
            applyDrop: { applyFeedListDropAction($0, home: home) }
        )
    }

    /// A tap on a row. Collapsed (iPhone), it also opens the article list — re-tapping the current
    /// row included — see `CompactSidebarSelection`.
    private func selectRow(_ item: SidebarItemID) {
        guard let key = item.selectionKey, let instance = item.rowSelection else { return }
        let tap = CompactSidebarSelection.tap(key: key, selectedKey: selectedRowKey)
        if tap.changesFilter {
            home.selectFilter(instance.filter, instance: instance)
            focusedPane.wrappedValue = .feedList
        }
        if tap.navigates { onOpenArticleList() }
    }

    /// `toggleFolderCollapsed`/`toggleTagExpanded` flip the state, so they only run when the
    /// requested state differs.
    private func setExpanded(_ item: SidebarItemID, _ expanded: Bool) {
        switch item {
        case .sectionHeader(.folders): foldersExpanded = expanded
        case .sectionHeader(.tags): tagsExpanded = expanded
        case .folder(let id):
            guard expanded == home.collapsedFolderIds.contains(id) else { return }
            home.viewModel.toggleFolderCollapsed(folderId: id)
        case .tag(let id):
            guard expanded != home.expandedTagIds.contains(id) else { return }
            home.viewModel.toggleTagExpanded(tagId: id)
        default:
            break
        }
    }

    private func renameEditor(for item: SidebarItemID) -> InlineRenameField? {
        switch item {
        case .folder(let id):
            return home.folders.first { $0.id == id }.flatMap(folderRenameEditor)
        case .tag(let id):
            return home.tags.first { $0.id == id }.flatMap(tagRenameEditor)
        case .feed(let id), .feedInTag(let id, _):
            guard let feed = home.feedsById[id], let instance = item.rowSelection else { return nil }
            return feedRenameEditor(feed, instance: instance)
        default:
            return nil
        }
    }
}
#endif
