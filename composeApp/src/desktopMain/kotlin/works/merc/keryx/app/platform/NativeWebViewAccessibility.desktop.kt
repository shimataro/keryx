package works.merc.keryx.app.platform

import io.github.kdroidfilter.webview.web.NativeWebView

/**
 * No-op: `PaneLayout.Triple` (the only layout desktop ever resolves) mounts exactly one reader
 * `WebView`, never an off-screen neighbour, so there is nothing here to hide.
 */
actual fun setNativeWebViewImportantForAccessibility(webView: NativeWebView, important: Boolean) = Unit
