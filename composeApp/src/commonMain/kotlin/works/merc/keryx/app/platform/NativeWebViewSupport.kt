package works.merc.keryx.app.platform

/**
 * Whether the article reader's native web view can actually be created on this machine.
 *
 * The reader is a native OS browser surface reached through a third-party library that ships one
 * prebuilt binary per platform/architecture pair. A pair the library has no binary for fails at
 * class-initialization time with an [UnsatisfiedLinkError] — an `Error`, raised from inside
 * composition, which Compose's default window exception handler turns into a modal dialog that
 * parks the event-dispatch thread, leaving the window up but permanently unresponsive.
 *
 * The concrete gap today is Linux on arm64: `wrywebview` ships `linux-x86-64`, `darwin-aarch64`,
 * `darwin-x86-64` and `win32-x86-64`, but no `linux-aarch64`. Checking up front lets
 * [works.merc.keryx.app.ui.home.ArticleDetailPaneContent] render the article with Compose instead
 * of letting the error escape, so every other part of the app keeps working there.
 *
 * This is a **workaround, not a fix**. The real fix is a backend that publishes an arm64 Linux
 * binary — the upstream library has since moved to one, but adopting it means replacing Compose
 * Desktop's own `application`/`Window` entry point with that project's windowing layer. See
 * `docs/known-issues.md`.
 */
expect fun isNativeWebViewSupported(): Boolean
