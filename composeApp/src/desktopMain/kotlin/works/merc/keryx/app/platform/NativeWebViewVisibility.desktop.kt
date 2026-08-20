package works.merc.keryx.app.platform

import io.github.kdroidfilter.webview.web.NativeWebView

/**
 * On desktop `NativeWebView` is a type alias for the library's `WryWebViewPanel`, an AWT
 * component, so visibility is just `java.awt.Component#isVisible`.
 */
actual fun setNativeWebViewVisible(webView: NativeWebView, visible: Boolean) {
    webView.isVisible = visible
}
