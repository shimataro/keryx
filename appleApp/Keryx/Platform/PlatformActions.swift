#if os(macOS)
import AppKit
#else
import UIKit
#endif
import Foundation
import KeryxShared

/// Copies `text` to the system pasteboard. macOS only for now — `docs/error-design.md`'s
/// "Notification Center" section documents desktop's own inline ✓ confirmation pattern (no
/// snackbar); callers show that confirmation themselves after this returns.
func copyToPasteboard(_ text: String) {
    #if os(macOS)
    NSPasteboard.general.clearContents()
    NSPasteboard.general.setString(text, forType: .string)
    #else
    UIPasteboard.general.string = text
    #endif
}

/// The one guarded handler behind every "Open in browser" (article URL) and "Open site" (feed site
/// URL) route: opens `urlString` only when `canOpenInBrowser` (http/https) allows it — an article or
/// site URL is unvalidated feed input, so a `file:`/`javascript:`/custom-scheme link is never handed
/// to the OS. Each route's enablement uses the same predicate. Matches Compose's
/// `openInBrowserIfAllowed` (`HomeCommon.kt`).
func openInBrowserIfAllowed(_ urlString: String?) {
    guard ArticleListModelKt.canOpenInBrowser(url: urlString) else { return }
    openInBrowser(urlString)
}

/// Opens `urlString` in the system's default browser. Unrestricted — for the app's own fixed links;
/// a URL that came from a feed goes through `openInBrowserIfAllowed` instead.
func openInBrowser(_ urlString: String?) {
    guard let urlString, let url = URL(string: urlString) else { return }
    #if os(macOS)
    NSWorkspace.shared.open(url)
    #else
    UIApplication.shared.open(url)
    #endif
}
