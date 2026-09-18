package works.merc.keryx.app.platform

import androidx.compose.ui.graphics.Color
import io.github.kdroidfilter.webview.web.NativeWebView

/**
 * Paints the article reader's native web view scrollbar in [color].
 *
 * The reader's document declares a single `color-scheme` so the engine paints its own scrollbar
 * and form controls in the app's theme rather than the OS's (see
 * [works.merc.keryx.app.ui.article.wrapArticleHtml]). On desktop that is the whole story, and this
 * `actual` is a no-op there. Android is the exception: `Widget.WebView` — the default style behind
 * every `android.webkit.WebView` — sets `scrollbars="horizontal|vertical"`, so the root frame's
 * scrollbar is drawn by the **Android View framework**, not by the engine, and no CSS reaches it.
 * Its thumb is the platform's own drawable tinted `?attr/colorControlNormal`, resolved against the
 * hosting Activity's theme — which `:androidApp` fixes to `Theme.Material.Light.NoActionBar`,
 * because the app's light/dark setting is its own and never the OS's. The thumb therefore stayed
 * light-theme dark grey over a dark reader background, all but invisible, until set explicitly here.
 *
 * Kept behind expect/actual because the mechanism is platform-specific
 * (`android.view.View#setVerticalScrollbarThumbDrawable`) and has no counterpart on the common
 * [NativeWebView] type.
 */
expect fun setNativeWebViewScrollbarColor(webView: NativeWebView, color: Color)
