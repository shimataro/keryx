import KeryxShared

/// Carries out every "Copy URL" route for an article (the menu bar's / keyboard's command, the
/// reader's toolbar button, the article row's context menu) as Kotlin's shared `articleUrlCopyPlan`
/// decides — the same decision Compose's `ArticleUrlCopier.kt` executes: an unusable URL copies
/// nothing, a usable one is always copied, and the reader's ✓ flashes only when the copied article
/// is the one the reader shows. Every copy that is written is also handed to `confirm`, the UI's one
/// copy confirmation (an iOS toast — see `HomeObservable.copyArticleUrl`), whichever article it was.
/// Pure (the pasteboard write, the pulse and the confirmation are injected) so `ArticleUrlCopyTests`
/// can cover it without the app.
enum ArticleUrlCopy {
    static func perform(
        url: String?,
        articleId: String,
        selectedId: String?,
        copy: (String) -> Void,
        pulse: () -> Void,
        confirm: () -> Void
    ) {
        let plan = ArticleListModelKt.articleUrlCopyPlan(url: url, articleId: articleId, displayedArticleId: selectedId)
        guard plan.writeClipboard, let url else { return }
        copy(url)
        if plan.flashCopied { pulse() }
        confirm()
    }
}
