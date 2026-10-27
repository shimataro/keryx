package works.merc.keryx.app.platform

import io.github.kdroidfilter.webview.web.NativeWebView
import io.github.kdroidfilter.webview.web.WebViewFactoryParam

/** The library's own default — see the `expect fun` KDoc in `ReaderWebViewFactory.kt`. */
actual fun readerWebViewFactory(onRenderProcessGone: () -> Unit): ((WebViewFactoryParam) -> NativeWebView)? = null
