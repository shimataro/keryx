package works.merc.keryx.app.platform

import android.content.Context
import android.graphics.Bitmap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.kdroidfilter.webview.web.NativeWebView
import io.github.kdroidfilter.webview.web.WebViewFactoryParam

actual fun readerWebViewFactory(onRenderProcessGone: () -> Unit): ((WebViewFactoryParam) -> NativeWebView)? =
    { param -> RenderProcessGoneWebView(param.context, onRenderProcessGone) }

/**
 * A WebView that wraps every client it is given in [RenderProcessGoneClient] — the library assigns its
 * own client right after the factory returns, with no hook of its own for `onRenderProcessGone`.
 * This subclass (and the wrapper) can go once the library handles the callback itself.
 */
private class RenderProcessGoneWebView(
    context: Context,
    private val onRenderProcessGone: () -> Unit,
) : WebView(context) {
    override fun setWebViewClient(client: WebViewClient) {
        super.setWebViewClient(RenderProcessGoneClient(client, onRenderProcessGone))
    }
}

/**
 * Forwards to [delegate] every callback the library's client overrides (`onPageStarted`,
 * `onPageFinished`, `doUpdateVisitedHistory`, `onReceivedError`, `shouldOverrideUrlLoading`), and
 * adds `onRenderProcessGone`: it reports the death and returns true, so Android keeps the app process
 * alive. Every other callback keeps `WebViewClient`'s default, as the library's own client does.
 */
private class RenderProcessGoneClient(
    private val delegate: WebViewClient,
    private val onRenderProcessGone: () -> Unit,
) : WebViewClient() {
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) =
        delegate.onPageStarted(view, url, favicon)

    override fun onPageFinished(view: WebView, url: String?) = delegate.onPageFinished(view, url)

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) =
        delegate.doUpdateVisitedHistory(view, url, isReload)

    override fun onReceivedError(view: WebView, request: WebResourceRequest?, error: WebResourceError?) =
        delegate.onReceivedError(view, request, error)

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
        delegate.shouldOverrideUrlLoading(view, request)

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRenderProcessGone()
        return true
    }
}
