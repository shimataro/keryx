#if os(macOS)
import AppKit
#else
import UIKit
#endif
import Foundation

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

/// Opens `urlString` in the system's default browser.
func openInBrowser(_ urlString: String?) {
    guard let urlString, let url = URL(string: urlString) else { return }
    #if os(macOS)
    NSWorkspace.shared.open(url)
    #else
    UIApplication.shared.open(url)
    #endif
}
