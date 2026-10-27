package works.merc.keryx.app.platform

import io.github.kdroidfilter.webview.web.NativeWebView
import io.github.kdroidfilter.webview.web.WebViewFactoryParam

/**
 * The factory the article reader passes to the web view library's `WebView` composable, or null for
 * the library's own default.
 *
 * Android's WebView renderer is a separate process that can die under a live app — the OS reclaiming
 * its memory, or a renderer crash. Unless some `WebViewClient.onRenderProcessGone` returns true for
 * every WebView it hosted, Android then kills the app process too; and the library's own client
 * (1.0.0-beta-02) does not implement that callback. The Android `actual` therefore builds a WebView
 * that slips a client handling it around whichever client the library installs, and reports the
 * death through [onRenderProcessGone] so the reader can rebuild that WebView (a dead one cannot be
 * reused) — see `ArticleWebView`. Desktop's engines have no such failure mode the app could recover
 * from, so its `actual` returns null.
 */
expect fun readerWebViewFactory(onRenderProcessGone: () -> Unit): ((WebViewFactoryParam) -> NativeWebView)?
