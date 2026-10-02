import Testing

/// Covers `ArticleUrlCopy`, which every article "Copy URL" route goes through, carrying out the shared
/// `articleUrlCopyPlan`: what reaches the pasteboard, when the reader's ✓ pulses, and that every
/// written copy is confirmed (the iOS toast), whether or not the reader shows that article.
@Suite
struct ArticleUrlCopyTests {
    /// Records what a `perform` call copied and how often it pulsed.
    private final class Recorder {
        var copied: [String] = []
        var pulses = 0
        var confirmations = 0
    }

    private func perform(url: String?, articleId: String, selectedId: String?) -> Recorder {
        let recorder = Recorder()
        ArticleUrlCopy.perform(
            url: url,
            articleId: articleId,
            selectedId: selectedId,
            copy: { recorder.copied.append($0) },
            pulse: { recorder.pulses += 1 },
            confirm: { recorder.confirmations += 1 }
        )
        return recorder
    }

    @Test(arguments: [nil, "", "   "] as [String?])
    func anUnusableUrlCopiesNothingAndDoesNotPulse(url: String?) {
        let recorder = perform(url: url, articleId: "a1", selectedId: "a1")

        #expect(recorder.copied.isEmpty)
        #expect(recorder.pulses == 0)
        #expect(recorder.confirmations == 0)
    }

    @Test
    func copyingTheSelectedArticlePulses() {
        let recorder = perform(url: "https://example.com/a1", articleId: "a1", selectedId: "a1")

        #expect(recorder.copied == ["https://example.com/a1"])
        #expect(recorder.pulses == 1)
        #expect(recorder.confirmations == 1)
    }

    @Test
    func copyingAnotherArticleCopiesWithoutPulsing() {
        let recorder = perform(url: "https://example.com/a2", articleId: "a2", selectedId: "a1")

        #expect(recorder.copied == ["https://example.com/a2"])
        #expect(recorder.pulses == 0)
        #expect(recorder.confirmations == 1, "confirmed even though the reader's ✓ does not flash")
    }

    @Test
    func copyingWithNothingSelectedCopiesWithoutPulsing() {
        let recorder = perform(url: "https://example.com/a2", articleId: "a2", selectedId: nil)

        #expect(recorder.copied == ["https://example.com/a2"])
        #expect(recorder.pulses == 0)
        #expect(recorder.confirmations == 1, "confirmed even though the reader's ✓ does not flash")
    }
}
