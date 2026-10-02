import Foundation
import SwiftUI

/// Posts a VoiceOver announcement at high priority. A copy confirmation is usually posted while a
/// context menu is closing, and the focus change that follows would otherwise interrupt a
/// default-priority announcement before it is spoken.
enum VoiceOverAnnouncement {
    @MainActor
    static func post(_ message: String) {
        var text = AttributedString(message)
        text.accessibilitySpeechAnnouncementPriority = .high
        AccessibilityNotification.Announcement(text).post()
    }
}
