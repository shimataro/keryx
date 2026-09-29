import KeryxShared
import SwiftUI

/// The notification center bell — lives in `ArticleListView`'s header at every layout width (see
/// `error-design.md`'s "Notification Center"). Only `ResetCloudData` is excluded from row-clicking
/// (destructive, own inline confirm); every other row's action runs on tap and closes the popover
/// afterward, matching Compose's own `onNavigated` (`NotificationCenterSheet.kt`).
struct NotificationBell: View {
    let home: HomeObservable
    let notifications: NotificationCenterObservable
    let settingsNavigation: SettingsNavigation
    var focusedPane: FocusState<HomeFocusedPane?>.Binding

    #if os(macOS)
    @Environment(\.openSettings) private var openSettings
    #endif
    @State private var isShowingPopover = false
    @State private var infoDialogMessage: String?
    @State private var confirmReset: AppNotification?
    @State private var nowMillis = Date().timeIntervalSince1970 * 1000

    private var unreadCount: Int { notifications.items.count }

    var body: some View {
        Button {
            isShowingPopover = true
        } label: {
            Image(systemName: "bell")
                .overlay(alignment: .topTrailing) {
                    if unreadCount > 0 {
                        Text("\(unreadCount)")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(2)
                            .background(Circle().fill(.red))
                            .offset(x: 6, y: -6)
                    }
                }
        }
        .help(L("home_notifications"))
        .accessibilityLabel(L("home_notifications"))
        .accessibilityValue(unreadCount > 0 ? "\(unreadCount)" : "")
        .task { await notifications.startObserving() }
        .popover(isPresented: $isShowingPopover) {
            popoverContent
        }
        .alert(L("notification_detail_title"), isPresented: Binding(get: { infoDialogMessage != nil }, set: { if !$0 { infoDialogMessage = nil } })) {
            Button(L("common_ok")) {}
        } message: {
            Text(infoDialogMessage ?? "")
        }
        .alert(
            L("settings_cloud_reset_confirm_title"),
            isPresented: Binding(get: { confirmReset != nil }, set: { if !$0 { confirmReset = nil } })
        ) {
            Button(L("settings_cloud_reset_confirm_action"), role: .destructive) {
                if let notification = confirmReset {
                    home.viewModel.resetCloudData()
                    notifications.dismiss(id: notification.id)
                }
            }
            Button(L("common_cancel"), role: .cancel) {}
        } message: {
            Text(L("settings_cloud_reset_confirm_body"))
        }
        // Refreshes every row's relative timestamp ("5 minutes ago" → "6 minutes ago") while the
        // popover is open, matching Compose's own 60-second tick (`NotificationCenterSheet.kt`).
        .task(id: isShowingPopover) {
            guard isShowingPopover else { return }
            while !Task.isCancelled {
                nowMillis = Date().timeIntervalSince1970 * 1000
                try? await Task.sleep(for: .seconds(60))
            }
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
        // `ResetCloudData` is deliberately not a row-wide tap target — it opens its own inline
        // confirm button instead, matching Compose's own non-clickable row + destructive "Reset"
        // button (`NotificationCenterSheet.kt:205-210,280`).
        if isResetCloudData(notification) {
            resetCloudDataRow(notification)
        } else {
            Button {
                handleAction(notification)
            } label: {
                rowLabel(notification)
            }
            .buttonStyle(.plain)
        }
    }

    private func isResetCloudData(_ notification: AppNotification) -> Bool {
        guard let action = notification.action else { return false }
        if case .resetCloudData = onEnum(of: action) { return true }
        return false
    }

    private func resetCloudDataRow(_ notification: AppNotification) -> some View {
        HStack(alignment: .top) {
            rowLabel(notification, showDismiss: false)
            Spacer()
            Button(L("settings_cloud_reset_confirm_action"), role: .destructive) {
                confirmReset = notification
            }
            .buttonStyle(.plain)
            .foregroundStyle(.red)
        }
    }

    private func rowLabel(_ notification: AppNotification, showDismiss: Bool = true) -> some View {
        HStack(alignment: .top) {
            Image(systemName: levelIcon(notification.level))
                .foregroundStyle(levelColor(notification.level))
            VStack(alignment: .leading, spacing: 2) {
                Text(notificationText(notification.text))
                    .multilineTextAlignment(.leading)
                Text(relativeTimeText(notification.timestampMillis))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            if showDismiss {
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
    }

    /// Mirrors Compose's own `formatRelativeTime` (`NotificationCenterSheet.kt`), bucketing through
    /// the shared `relativeTimeOf` so both apps choose the same label for the same age, and reusing
    /// Compose's own `<plurals>` resources (`time_minutes_ago`/`time_hours_ago`/`time_days_ago`)
    /// directly — `LF` resolves the right plural form itself (see its own doc).
    private func relativeTimeText(_ timestampMillis: Int64) -> String {
        switch onEnum(of: RelativeTimeKt.relativeTimeOf(diffMillis: Int64(nowMillis) - timestampMillis)) {
        case .now: return L("time_now")
        case .minutes(let m): return LF("time_minutes_ago", Int64(m.count))
        case .hours(let h): return LF("time_hours_ago", Int64(h.count))
        case .days(let d): return LF("time_days_ago", Int64(d.count))
        case .absolute:
            let date = Date(timeIntervalSince1970: Double(timestampMillis) / 1000)
            return date.formatted(date: .abbreviated, time: .shortened)
        }
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
            isShowingPopover = false
        case .showFeedDetail(let a):
            // Selects unconditionally, whether or not the feed is currently in `home.feeds` (a sync
            // merge could still be catching up) — matches Compose's own unconditional select
            // (`HomeScreen.kt:755-759`), which also focuses the article-list pane so the keyboard
            // immediately lands where the selected feed's articles now show.
            home.viewModel.selectFilter(
                filter: ArticleFilterFeed(feedId: a.feedId),
                instance: FeedListRowSelectionFeedInFolderGroup(feedId: a.feedId)
            )
            focusedPane.wrappedValue = .articleList
            isShowingPopover = false
        case .showSettingsTab(let a):
            // Selected before opening, so a Settings window that isn't open yet still opens on it.
            settingsNavigation.show(tabId: a.tabId)
            #if os(macOS)
            openSettings()
            #endif
            isShowingPopover = false
        case .showInfoDialog(let a):
            infoDialogMessage = infoDialogText(a.detail)
            isShowingPopover = false
        case .resetCloudData:
            confirmReset = notification
        }
    }
}
