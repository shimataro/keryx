package works.merc.keryx.app.platform

import io.github.kdroidfilter.webview.web.NativeWebView

/**
 * Marks the article reader's native web view as reachable (or not) by accessibility services.
 *
 * The reader's pager keeps the pages either side of the one on screen mounted
 * (`beyondViewportPageCount = 1` — see [works.merc.keryx.app.ui.home.ArticleDetailPane]), each a
 * real `WebView`. Compose's own semantics tree has no reach into that view's accessibility nodes —
 * it is exposed through the platform's own accessibility bridge for embedded views — so hiding an
 * inactive page from a Compose-drawn screen reader affordance (`clearAndSetSemantics {}`) does not
 * by itself stop a linear accessibility traversal from continuing into a WebView that is still
 * mounted just outside the visible bounds. This sets the platform's own "not important for
 * accessibility, and hide every descendant too" flag directly on the native view instead.
 *
 * Kept behind expect/actual because the mechanism is platform-specific
 * (`android.view.View#setImportantForAccessibility` on Android) and has no counterpart on the
 * common [NativeWebView] type. Desktop's `WryWebViewPanel` is a heavyweight AWT component that is
 * never composed off-screen in the first place (`PaneLayout.Triple` mounts exactly one), so its
 * `actual` is a no-op.
 */
expect fun setNativeWebViewImportantForAccessibility(webView: NativeWebView, important: Boolean)
