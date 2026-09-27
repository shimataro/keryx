import KeryxShared
import SwiftUI

/// The add-feed sheet: type a URL → preview (a single feed, or candidates discovered on an HTML
/// page) → pick candidates → subscribe. State machine lives entirely in `AddFeedController`; this
/// view only renders it and forwards actions. See `AddFeedController.kt`'s own doc.
struct AddFeedSheet: View {
    let home: HomeObservable
    @Binding var isPresented: Bool

    @State private var addFeed: AddFeedObservable

    init(home: HomeObservable, isPresented: Binding<Bool>) {
        self.home = home
        self._isPresented = isPresented
        self._addFeed = State(initialValue: AddFeedObservable(controller: home.makeAddFeedController()))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Add Feed")
                .font(.headline)

            TextField("Feed URL", text: urlBinding)
                .textFieldStyle(.roundedBorder)
                .disabled(addFeed.state.phase != nil)
                .onSubmit { submit() }

            if let error = addFeed.state.error {
                Text(error.messageText)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            if let partial = addFeed.state.partialResult {
                Text("\(partial.first?.intValue ?? 0) succeeded, \(partial.second?.intValue ?? 0) failed")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }

            candidatesList

            if addFeed.state.phase != nil {
                ProgressView()
            }

            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { isPresented = false }
                Button(addFeed.state.hasResult ? "Subscribe" : "Preview") { submit() }
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

    @ViewBuilder
    private var candidatesList: some View {
        if let preview = addFeed.state.preview, case let .multiple(multiple) = onEnum(of: preview) {
            List(multiple.candidates, id: \.url) { candidate in
                Toggle(isOn: candidateBinding(candidate.url)) {
                    VStack(alignment: .leading) {
                        Text(candidate.title ?? candidate.url)
                        Text(candidate.url).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .frame(minHeight: 120, maxHeight: 240)
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
