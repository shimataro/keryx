package works.merc.keryx.app.platform

/**
 * Desktop has no share sheet ([platformSupportsShare] is `false`), so no route ever reaches this;
 * it exists only to satisfy the `expect`, and does nothing.
 */
actual object ShareSheet {
    actual fun shareText(text: String, subject: String?, chooserTitle: String) = Unit
}
