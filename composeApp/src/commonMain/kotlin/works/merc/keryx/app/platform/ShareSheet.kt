package works.merc.keryx.app.platform

/**
 * Hands plain text to the OS share sheet — the platform-side execution behind "Share" (see
 * `ui/home/ArticleSharer.kt`, the one handler every share route calls). Only reached where
 * [platformSupportsShare] is `true`.
 */
expect object ShareSheet {
    /**
     * Opens the share sheet for [text].
     *
     * @param text The text to share (an article's URL).
     * @param subject A title for the shared text (the article's title), or `null` when there is none.
     * @param chooserTitle The share sheet's own heading, where the OS still shows one.
     */
    fun shareText(text: String, subject: String?, chooserTitle: String)
}
