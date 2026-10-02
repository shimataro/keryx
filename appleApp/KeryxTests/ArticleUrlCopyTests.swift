import Testing

/// Covers `ArticleUrlCopy`, which every article "Copy URL" route goes through, carrying out the shared
/// `articleUrlCopyPlan`: what reaches the pasteboard, when the reader's ✓ pulses, and when the copy is
/// confirmed in-app — on iOS every copy (the toast), on macOS only one the reader's ✓ does not
/// confirm (the VoiceOver announcement). The full truth table is `ArticleUrlCopyPlanTest` in
/// `:shared`; these pin that the Swift side passes the right platform facts and honours the plan.
@Suite
struct ArticleUrlCopyTests {
    /// Records what a `perform` call copied, how often it pulsed and how often it confirmed.
    private final class Recorder {
        var copied: [String] = []
        var pulses = 0
        var confirmations = 0
    }

    private enum Platform { case macOS, iOS, current }

    private func perform(url: String?, articleId: String, selectedId: String?, on platform: Platform) -> Recorder {
        let recorder = Recorder()
        let copy: (String) -> Void = { recorder.copied.append($0) }
        let pulse = { recorder.pulses += 1 }
        let confirm = { recorder.confirmations += 1 }
        switch platform {
        case .current:
            ArticleUrlCopy.perform(
                url: url, articleId: articleId, selectedId: selectedId,
                copy: copy, pulse: pulse, confirm: confirm
            )
        case .macOS, .iOS:
            ArticleUrlCopy.perform(
                url: url, articleId: articleId, selectedId: selectedId,
                platformShowsOwnConfirmation: false,
                inlineCheckConfirms: platform == .macOS,
                copy: copy, pulse: pulse, confirm: confirm
            )
        }
        return recorder
    }

    @Test(arguments: [nil, "", "   "] as [String?])
    func anUnusableUrlCopiesNothingAndDoesNotPulse(url: String?) {
        let recorder = perform(url: url, articleId: "a1", selectedId: "a1", on: .iOS)

        #expect(recorder.copied.isEmpty)
        #expect(recorder.pulses == 0)
        #expect(recorder.confirmations == 0)
    }

    @Test
    func onMacOSCopyingTheSelectedArticlePulsesWithoutAnInAppConfirmation() {
        let recorder = perform(url: "https://example.com/a1", articleId: "a1", selectedId: "a1", on: .macOS)

        #expect(recorder.copied == ["https://example.com/a1"])
        #expect(recorder.pulses == 1)
        #expect(recorder.confirmations == 0, "the reader's ✓ is the confirmation")
    }

    @Test
    func onMacOSCopyingAnArticleTheReaderDoesNotShowIsConfirmed() {
        // A context menu opened from the keyboard or VoiceOver does not select its row.
        let recorder = perform(url: "https://example.com/a2", articleId: "a2", selectedId: "a1", on: .macOS)

        #expect(recorder.copied == ["https://example.com/a2"])
        #expect(recorder.pulses == 0)
        #expect(recorder.confirmations == 1, "the announcement stands in for the ✓ that does not flash")
    }

    @Test
    func onIOSEveryCopyIsConfirmed() {
        let selected = perform(url: "https://example.com/a1", articleId: "a1", selectedId: "a1", on: .iOS)
        #expect(selected.pulses == 1)
        #expect(selected.confirmations == 1)

        let other = perform(url: "https://example.com/a2", articleId: "a2", selectedId: "a1", on: .iOS)
        #expect(other.copied == ["https://example.com/a2"])
        #expect(other.pulses == 0)
        #expect(other.confirmations == 1, "confirmed even though the reader's ✓ does not flash")

        let none = perform(url: "https://example.com/a2", articleId: "a2", selectedId: nil, on: .iOS)
        #expect(none.pulses == 0)
        #expect(none.confirmations == 1)
    }

    @Test
    func theDefaultsAreThisPlatformsSharedFacts() {
        let recorder = perform(url: "https://example.com/a1", articleId: "a1", selectedId: "a1", on: .current)

        #if os(macOS)
        #expect(recorder.confirmations == 0)
        #else
        #expect(recorder.confirmations == 1)
        #endif
        #expect(recorder.pulses == 1)
    }

    // MARK: - ArticleUrlCopyConfirmation

    /// Records what an `ArticleUrlCopyConfirmation` delivered.
    @MainActor
    private final class DeliveryLog {
        var announcements: [String] = []
        var toasts: [String] = []

        func confirmation(withToast: Bool) -> ArticleUrlCopyConfirmation {
            ArticleUrlCopyConfirmation(
                announce: { self.announcements.append($0) },
                showToast: withToast ? { self.toasts.append($0) } : nil
            )
        }
    }

    /// iOS: one copy shows the toast once and announces the same message once — no second
    /// announcement from the toast itself.
    @MainActor
    @Test
    func oneCopyWithAToastShowsItAndAnnouncesOnce() {
        let log = DeliveryLog()
        let confirmation = log.confirmation(withToast: true)

        ArticleUrlCopy.perform(
            url: "https://example.com/a2", articleId: "a2", selectedId: "a1",
            platformShowsOwnConfirmation: false, inlineCheckConfirms: false,
            copy: { _ in }, pulse: {}, confirm: { confirmation.confirm() }
        )

        #expect(log.toasts.count == 1)
        #expect(log.announcements.count == 1)
        #expect(log.toasts == log.announcements)
    }

    /// macOS: no toast — the confirmation is the announcement alone.
    @MainActor
    @Test
    func aConfirmationWithoutAToastOnlyAnnounces() {
        let log = DeliveryLog()
        let confirmation = log.confirmation(withToast: false)

        ArticleUrlCopy.perform(
            url: "https://example.com/a2", articleId: "a2", selectedId: "a1",
            platformShowsOwnConfirmation: false, inlineCheckConfirms: true,
            copy: { _ in }, pulse: {}, confirm: { confirmation.confirm() }
        )

        #expect(log.toasts.isEmpty)
        #expect(log.announcements.count == 1)
    }
}
