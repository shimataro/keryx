package works.merc.keryx.app.platform

import io.github.kdroidfilter.webview.web.NativeWebView

/** No-op — see the `expect fun` KDoc in `NativeWebViewScroll.kt`. */
actual fun observeNativeWebViewScroll(webView: NativeWebView, listener: NativeWebViewScrollListener) = Unit

actual fun nativeWebViewContentHeight(webView: NativeWebView): Int = 0

actual fun scrollNativeWebViewTo(webView: NativeWebView, fraction: Double) = Unit

actual fun isNativeWebViewScreenReaderActive(webView: NativeWebView): Boolean = false
