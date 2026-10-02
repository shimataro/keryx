import KeryxShared

/// Carries out every "Copy URL" route for an article (the menu bar's / keyboard's command, the
/// reader's toolbar button, the article row's context menu) as Kotlin's shared `articleUrlCopyPlan`
/// decides — the same decision Compose's `ArticleUrlCopier.kt` executes: an unusable URL copies
/// nothing, a usable one is always copied, the reader's ✓ flashes only when the copied article is
/// the one the reader shows, and `confirm` — the UI's one in-app copy confirmation (see
/// `HomeObservable.copyArticleUrl`) — runs only when the plan's `confirmInApp` says so. The platform
/// facts the plan needs come from the shared `platform/PlatformOs.kt`, exactly as Compose reads them;
/// they are parameters only so `ArticleUrlCopyTests` can cover both platforms' rules on either one.
/// Pure (the pasteboard write, the pulse and the confirmation are injected) so the tests can cover it
/// without the app.
enum ArticleUrlCopy {
    static func perform(
        url: String?,
        articleId: String,
        selectedId: String?,
        platformShowsOwnConfirmation: Bool = PlatformOs_appleKt.platformShowsOwnCopyConfirmation,
        inlineCheckConfirms: Bool = !PlatformOs_appleKt.isTouchPrimary,
        copy: (String) -> Void,
        pulse: () -> Void,
        confirm: () -> Void
    ) {
        let plan = ArticleListModelKt.articleUrlCopyPlan(
            url: url,
            articleId: articleId,
            displayedArticleId: selectedId,
            platformShowsOwnConfirmation: platformShowsOwnConfirmation,
            inlineCheckConfirms: inlineCheckConfirms
        )
        guard plan.writeClipboard, let url else { return }
        copy(url)
        if plan.flashCopied { pulse() }
        if plan.confirmInApp { confirm() }
    }
}
