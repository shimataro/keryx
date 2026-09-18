package works.merc.keryx.app.platform

import androidx.compose.ui.graphics.Color
import io.github.kdroidfilter.webview.web.NativeWebView

/**
 * No-op: WebView2/WebKit/WebKitGTK already paint their own scrollbar from the reader document's
 * `color-scheme` (see [works.merc.keryx.app.ui.article.wrapArticleHtml]), unlike Android's
 * `android.webkit.WebView`, whose root-frame scrollbar is drawn by the View framework instead —
 * see the `actual fun` KDoc in `NativeWebViewScrollbar.kt` for why that needs this call.
 */
actual fun setNativeWebViewScrollbarColor(webView: NativeWebView, color: Color) = Unit
