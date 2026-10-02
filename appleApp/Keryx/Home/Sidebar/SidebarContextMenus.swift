#if os(iOS)
import KeryxShared
import UIKit

/// The iOS sidebar's context menus, as `UIMenu`s for its collection view. The items, their order,
/// enablement and checkmarks are the same as the macOS source list's SwiftUI menus
/// (`FeedListView+SourceList.swift`), which in turn follow Compose's `FeedListDragAndDrop.kt` — keep
/// all three in step.
@MainActor
enum SidebarContextMenus {
    static func menu(for item: SidebarItemID, home: HomeObservable, dialogs: SidebarDialogState) -> UIMenu? {
        guard let instance = item.rowSelection else { return nil }
        switch item {
        case .feed(let id), .feedInTag(let id, _):
            return home.feedsById[id].map { feedMenu($0, instance: instance, home: home, dialogs: dialogs) }
        case .folder(let id):
            guard let folder = home.folders.first(where: { $0.id == id }) else { return nil }
            return UIMenu(children: [
                UIAction(title: L("home_edit_folder_menu"), image: UIImage(systemName: "pencil")) { _ in dialogs.startRename(instance) },
                UIAction(title: L("home_delete_folder_menu"), image: UIImage(systemName: "trash"), attributes: .destructive) { _ in dialogs.deletingFolder = folder },
            ])
        case .tag(let id):
            guard let tag = home.tags.first(where: { $0.id == id }) else { return nil }
            return UIMenu(children: [
                UIAction(title: L("home_edit_tag_menu"), image: UIImage(systemName: "pencil")) { _ in dialogs.startRename(instance) },
                colorMenu(for: tag, home: home),
                UIAction(title: L("home_delete_tag_menu"), image: UIImage(systemName: "trash"), attributes: .destructive) { _ in dialogs.deletingTag = tag },
            ])
        case .all, .starred, .sectionHeader, .noFolderHeader:
            return nil
        }
    }

    /// "Change color ▸" with the swatches as a palette — a row of colored dots, the current one checked —
    /// in place of a popover: a popover is a poor fit on an iPhone, and a menu item reads by name for
    /// VoiceOver where a bare dot would not.
    private static func colorMenu(for tag: Tags, home: HomeObservable) -> UIMenu {
        let colors: [String?] = [nil] + TagColorsKt.TAG_COLOR_PALETTE
        let actions = colors.map { hex in
            UIAction(
                title: TagColorNames.name(for: hex),
                image: UIImage(systemName: "circle.fill")?
                    .withTintColor(UIColor(colorFromHex(hex)), renderingMode: .alwaysOriginal),
                state: tag.color == hex ? .on : .off
            ) { _ in
                home.viewModel.updateTag(id: tag.id, name: tag.name, color: hex)
            }
        }
        return UIMenu(title: L("home_change_tag_color_menu"), image: UIImage(systemName: "paintpalette"), children: [
            UIMenu(options: [.displayInline, .displayAsPalette], children: actions),
        ])
    }

    /// Refresh, Move to Folder ▸, Assign tags ▸, a separator, the URL/site actions, a separator,
    /// Rename, a separator, Unsubscribe — `FeedListDragAndDrop.kt:553-593`'s order.
    private static func feedMenu(
        _ feed: Feeds,
        instance: FeedListRowSelection,
        home: HomeObservable,
        dialogs: SidebarDialogState
    ) -> UIMenu {
        let moveToFolder = UIMenu(title: L("home_move_to_folder"), image: UIImage(systemName: "folder"), children: [
            UIAction(title: L("home_no_folder"), state: feed.folder_id == nil ? .on : .off) { _ in
                home.viewModel.moveFeed(feedId: feed.id, folderId: nil, targetFeedId: nil)
            },
        ] + home.sidebar.sortedFolders.map { folder in
            UIAction(title: folder.name, state: feed.folder_id == folder.id ? .on : .off) { _ in
                home.viewModel.moveFeed(feedId: feed.id, folderId: folder.id, targetFeedId: nil)
            }
        } + [
            UIAction(title: L("home_new_folder"), image: UIImage(systemName: "folder.badge.plus")) { _ in dialogs.creatingFolderForFeed = feed },
        ])
        let attachedTagIds = home.feedTagMap[feed.id] ?? []
        let assignTags = UIMenu(title: L("home_assign_tags"), image: UIImage(systemName: "tag"), children: home.sidebar.sortedTags.map { tag in
            let attached = attachedTagIds.contains(tag.id)
            return UIAction(title: tag.name, state: attached ? .on : .off) { _ in
                home.viewModel.setFeedTag(feedId: feed.id, tagId: tag.id, attached: !attached)
            }
        } + [
            UIAction(title: L("home_new_tag"), image: UIImage(systemName: "plus")) { _ in dialogs.creatingTagForFeed = feed },
        ])
        let siteAttributes: UIMenuElement.Attributes = ArticleListModelKt.hasUsableUrl(url: feed.site_url) ? [] : .disabled
        // The warning icon in the row has no hover tooltip on touch, so an erroring feed's menu says
        // why: the same two localized reasons as the row's accessibility label (never the raw
        // `last_error` text).
        let status = SidebarRowStaticContent(feed: feed)
        let reason = status.isErroring ? L(status.isGone ? "home_feed_gone" : "home_feed_error") : ""
        return UIMenu(title: reason, children: [
            UIMenu(options: .displayInline, children: [
                UIAction(title: L("home_refresh"), image: UIImage(systemName: "arrow.clockwise")) { _ in home.refreshFeed(feed) },
                moveToFolder,
                assignTags,
            ]),
            UIMenu(options: .displayInline, children: [
                UIAction(title: L("home_copy_feed_url"), image: UIImage(systemName: "link")) { _ in copyToPasteboard(feed.url) },
                UIAction(title: L("home_copy_site_url"), image: UIImage(systemName: "doc.on.doc"), attributes: siteAttributes) { _ in
                    if let site = feed.site_url { copyToPasteboard(site) }
                },
                UIAction(title: L("home_open_site"), image: UIImage(systemName: "safari"), attributes: siteAttributes) { _ in
                    if let site = feed.site_url { openInBrowser(site) }
                },
            ]),
            UIMenu(options: .displayInline, children: [
                UIAction(title: L("home_rename_feed"), image: UIImage(systemName: "pencil")) { _ in dialogs.startRename(instance) },
            ]),
            UIMenu(options: .displayInline, children: [
                UIAction(title: L("home_unsubscribe_menu"), image: UIImage(systemName: "trash"), attributes: .destructive) { _ in dialogs.unsubscribingFeed = feed },
            ]),
        ])
    }
}
#endif
