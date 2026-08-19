package works.merc.keryx.app.platform

import io.github.kdroidfilter.webview.web.NativeWebView

/**
 * Shows or hides the article reader's native web view.
 *
 * Only Compose Desktop needs this: its `SwingInteropContainer` paints the heavyweight AWT surface
 * at its default (0,0) position before the real layout bounds are applied (JetBrains YouTrack
 * CMP-5780), so the reader is created hidden and revealed once its layout position is known — see
 * [works.merc.keryx.app.ui.home.ArticleDetailPane]. Platforms whose web view is an ordinary
 * in-tree view have no such flash and can implement this as a no-op.
 *
 * Kept behind expect/actual because the underlying visibility switch is platform-specific
 * (`java.awt.Component#isVisible` on desktop) and has no counterpart on the common
 * [NativeWebView] type.
 */
expect fun setNativeWebViewVisible(webView: NativeWebView, visible: Boolean)
