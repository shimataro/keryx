package works.merc.keryx.app.platform

import io.github.kdroidfilter.webview.web.NativeWebView

/**
 * No-op: the desktop version of this hides the CMP-5780 initial-position flash of Compose
 * Desktop's `SwingInteropContainer`. Android's WebView is an ordinary in-tree view with no
 * equivalent interop flash.
 */
actual fun setNativeWebViewVisible(webView: NativeWebView, visible: Boolean) = Unit
