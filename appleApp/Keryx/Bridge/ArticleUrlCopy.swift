import KeryxShared

/// The one decision behind every "Copy URL" route for an article (the menu bar's / keyboard's
/// command, the reader's toolbar button, the article row's context menu), mirroring Compose's
/// shared copy handler (`ArticleUrlCopier.kt`): an unusable URL copies nothing, a usable one is always
/// copied, and the reader's ✓ flashes only when the copied article is the one the reader shows —
/// copying some other row's URL must not confirm on a button that would copy a different URL.
/// Pure (the pasteboard write and the pulse are injected) so `ArticleUrlCopyTests` can cover it
/// without the app.
enum ArticleUrlCopy {
    static func perform(
        url: String?,
        articleId: String,
        selectedId: String?,
        copy: (String) -> Void,
        pulse: () -> Void
    ) {
        guard let url, ArticleListModelKt.hasUsableUrl(url: url) else { return }
        copy(url)
        if articleId == selectedId { pulse() }
    }
}
