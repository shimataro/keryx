import KeryxShared
import SwiftUI

/// The notification center bell — lives in `ArticleListView`'s header at every layout width (see
/// `error-design.md`'s "Notification Center"). Only `ResetCloudData` is excluded from row-clicking
/// (destructive, own inline confirm); every other row's action runs on tap.
struct NotificationBell: View {
    let home: HomeObservable
    let notifications: NotificationCenterObservable

    #if os(macOS)
    @Environment(\.openSettings) private var openSettings
    #endif
    @State private var isShowingPopover = false
    @State private var infoDialogMessage: String?
    @State private var confirmReset = false

    private var unreadCount: Int { notifications.items.count }

    var body: some View {
        Button {
            isShowingPopover = true
        } label: {
            ZStack(alignment: .topTrailing) {
                Image(systemName: "bell")
                if unreadCount > 0 {
                    Circle().fill(.red).frame(width: 8, height: 8)
                }
            }
        }
        .buttonStyle(.borderless)
        .task { await notifications.startObserving() }
        .popover(isPresented: $isShowingPopover) {
            popoverContent
        }
        .alert(L("notification_detail_title"), isPresented: Binding(get: { infoDialogMessage != nil }, set: { if !$0 { infoDialogMessage = nil } })) {
            Button(L("common_ok")) {}
        } message: {
            Text(infoDialogMessage ?? "")
        }
        .alert(L("settings_cloud_reset_confirm_title"), isPresented: $confirmReset) {
            Button(L("settings_cloud_reset_confirm_action"), role: .destructive) { home.viewModel.resetCloudData() }
            Button(L("common_cancel"), role: .cancel) {}
        } message: {
            Text(L("settings_cloud_reset_confirm_body"))
        }
    }

    @ViewBuilder
    private var popoverContent: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(L("notification_detail_title")).font(.headline)
                Spacer()
                if !notifications.items.isEmpty {
                    Button(L("notification_dismiss_all")) { notifications.dismissAll() }
                        .font(.caption)
                }
            }
            .padding()

            if notifications.items.isEmpty {
                Text(L("notification_empty"))
                    .foregroundStyle(.secondary)
                    .padding()
            } else {
                List(notifications.items, id: \.id) { notification in
                    row(notification)
                }
            }
        }
        .frame(minWidth: 320, minHeight: 200)
    }

    @ViewBuilder
    private func row(_ notification: AppNotification) -> some View {
        Button {
            handleAction(notification)
        } label: {
            HStack(alignment: .top) {
                Image(systemName: levelIcon(notification.level))
                    .foregroundStyle(levelColor(notification.level))
                Text(notificationText(notification.text))
                    .multilineTextAlignment(.leading)
                Spacer()
                Button {
                    notifications.dismiss(id: notification.id)
                } label: {
                    Image(systemName: "xmark.circle.fill")
                }
                .buttonStyle(.plain)
                .help(L("notification_dismiss"))
            }
        }
        .buttonStyle(.plain)
    }

    private func levelIcon(_ level: AppNotificationLevel) -> String {
        level == .error ? "xmark.octagon.fill" : level == .warning ? "exclamationmark.triangle.fill" : "info.circle.fill"
    }

    private func levelColor(_ level: AppNotificationLevel) -> Color {
        level == .error ? .red : level == .warning ? .orange : .blue
    }

    private func handleAction(_ notification: AppNotification) {
        guard let action = notification.action else { return }
        switch onEnum(of: action) {
        case .openUrl(let a):
            openInBrowser(a.url)
        case .showFeedDetail(let a):
            if let feed = home.feeds.first(where: { $0.id == a.feedId }) {
                home.viewModel.selectFilter(
                    filter: ArticleFilterFeed(feedId: feed.id),
                    instance: FeedListRowSelectionFeedInFolderGroup(feedId: feed.id)
                )
            }
            isShowingPopover = false
        case .showSettingsTab:
            // The settings window has no per-tab deep link from here yet (SettingsView always
            // opens on its first tab) — this at least opens the window, a reasonable simplification
            // for now (see M5's known gaps). No settings window exists on iOS at all (`KeryxApp`'s
            // `Settings` scene is macOS-only).
            #if os(macOS)
            openSettings()
            #endif
            isShowingPopover = false
        case .showInfoDialog(let a):
            infoDialogMessage = infoDialogText(a.detail)
        case .resetCloudData:
            confirmReset = true
        }
    }
}
