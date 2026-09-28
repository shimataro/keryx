import KeryxShared
import SwiftUI

/// The add-feed sheet: type a URL → preview (a single feed, or candidates discovered on an HTML
/// page) → pick candidates → subscribe. State machine lives entirely in `AddFeedController`; this
/// view only renders it and forwards actions. See `AddFeedController.kt`'s own doc.
struct AddFeedSheet: View {
    let home: HomeObservable
    @Binding var isPresented: Bool

    @State private var addFeed: AddFeedObservable
    @FocusState private var urlFieldFocused: Bool

    init(home: HomeObservable, isPresented: Binding<Bool>) {
        self.home = home
        self._isPresented = isPresented
        self._addFeed = State(initialValue: AddFeedObservable(controller: home.makeAddFeedController()))
    }

    private var alreadySubscribed: Bool {
        AddFeedPreviewResolverKt.addFeedAlreadySubscribed(url: addFeed.state.url, feeds: home.feeds)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(L("home_add_feed"))
                .font(.headline)

            // The URL field stays editable through preview, matching Compose's own dialog — only
            // subscribing (not previewing) disables it, unlike the earlier `phase != nil` check.
            TextField(L("home_add_feed_hint"), text: urlBinding)
                .textFieldStyle(.roundedBorder)
                .focused($urlFieldFocused)
                .disabled(addFeed.state.phase == .subscribing)
                .onSubmit { submit() }
                .task { urlFieldFocused = true }

            if alreadySubscribed {
                Text(L("home_already_subscribed"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let phase = addFeed.state.phase {
                HStack(spacing: 6) {
                    ProgressView().controlSize(.small)
                    Text(L(phase == .previewing ? "home_add_feed_loading_preview" : "home_add_feed_loading_subscribe"))
                        .font(.caption)
                }
            }

            previewContent

            if let error = addFeed.state.error {
                Text(errorKindMessage(error.errorKind))
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            if let partial = addFeed.state.partialResult {
                Text(LF("apple_add_feed_partial_result", Int64(partial.first?.intValue ?? 0), Int64(partial.second?.intValue ?? 0)))
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            HStack {
                Spacer()
                Button(L("common_cancel"), role: .cancel) { isPresented = false }
                Button(L(addFeed.state.hasResult ? "home_add_feed_subscribe" : "home_add_feed_confirm")) { submit() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!addFeed.state.confirmEnabled)
            }
        }
        .padding()
        .frame(minWidth: 420)
        .task { await addFeed.startObserving() }
    }

    private var urlBinding: Binding<String> {
        Binding(
            get: { addFeed.state.url },
            set: { addFeed.controller.setUrl(url: $0) }
        )
    }

    /// The single-feed preview (title + article count) or the discovered-candidates list —
    /// matches Compose's own `when (preview)` (`AddFeedDialog.kt`).
    @ViewBuilder
    private var previewContent: some View {
        if let preview = addFeed.state.preview {
            switch onEnum(of: preview) {
            case .single(let single):
                VStack(alignment: .leading, spacing: 2) {
                    Text(single.title).font(.subheadline.bold())
                    Text(LF("apple_add_feed_article_count", Int64(single.articleCount)))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            case .multiple(let multiple):
                candidatesList(multiple.candidates)
            case .failed:
                EmptyView()
            }
        }
    }

    private func candidatesList(_ candidates: [DiscoveredFeedLink]) -> some View {
        let selectedCount = candidates.filter { addFeed.state.selectedCandidates.contains($0.url) }.count
        let allSelected = !candidates.isEmpty && selectedCount == candidates.count

        return VStack(alignment: .leading, spacing: 8) {
            Text(L("home_add_feed_links_found")).font(.subheadline.bold())
            Text(L("home_add_feed_select_links"))
                .font(.caption)
                .foregroundStyle(.secondary)

            HStack {
                Button(L(allSelected ? "home_add_feed_clear_all" : "home_add_feed_select_all")) {
                    if allSelected {
                        addFeed.controller.clearCandidates()
                    } else {
                        addFeed.controller.selectAllCandidates()
                    }
                }
                Spacer()
                Text(LF("home_add_feed_selected_count", Int64(selectedCount), Int64(candidates.count)))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            List(candidates, id: \.url) { candidate in
                Toggle(isOn: candidateBinding(candidate.url)) {
                    VStack(alignment: .leading) {
                        Text(candidate.title ?? candidate.url)
                        HStack(spacing: 4) {
                            if let typeLabel = feedTypeLabel(candidate.type) {
                                Text(typeLabel)
                            }
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    }
                }
            }
            .frame(minHeight: 120, maxHeight: 240)
        }
    }

    private func feedTypeLabel(_ type: DiscoveredFeedType?) -> String? {
        switch type {
        case .rss: return L("home_add_feed_type_rss")
        case .atom: return L("home_add_feed_type_atom")
        case nil: return nil
        default: return nil
        }
    }

    private func candidateBinding(_ url: String) -> Binding<Bool> {
        Binding(
            get: { addFeed.state.selectedCandidates.contains(url) },
            set: { addFeed.controller.toggleCandidate(candidateUrl: url, checked: $0) }
        )
    }

    @MainActor
    private func submit() {
        let controller = addFeed.controller
        Task { @MainActor in
            let closeOnSuccess = try? await controller.submit()
            if closeOnSuccess == true {
                isPresented = false
            }
        }
    }
}
