package works.merc.keryx.app.platform

import android.view.View
import io.github.kdroidfilter.webview.web.NativeWebView

/**
 * On Android `NativeWebView` is a type alias for `android.webkit.WebView`, an ordinary `View`.
 * `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS` removes it — and its whole subtree, which is
 * where the article body actually lives — from the accessibility tree entirely, rather than merely
 * from Compose's own semantics (which a native view's accessibility nodes bypass regardless).
 * `IMPORTANT_FOR_ACCESSIBILITY_AUTO` restores the platform's normal default once the page becomes
 * the one actually on screen.
 */
actual fun setNativeWebViewImportantForAccessibility(webView: NativeWebView, important: Boolean) {
    webView.importantForAccessibility =
        if (important) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
}
