import Foundation

/// When a newly raised warning/error (`NotificationAlerts.alertToSurface`) may be announced in the
/// iOS alert toast, and when it counts as announced — the iOS counterpart of Android's
/// `ForegroundAlertSnackbar` (`HomeScreen.kt`). The notification center's own entry point sits at
/// the bottom of the sidebar, which an iPhone shows only at the root and an iPad can hide, so a
/// problem raised by background work would otherwise go unnoticed until the user looked there.
enum AlertToastAnnouncement {
    /// How long the alert toast stays up: longer than the copy toast, since it is read rather than
    /// glanced at.
    static let duration: Duration = .seconds(4)

    /// Whether announcing is allowed right now: only while the scene is active and the Settings sheet
    /// is not covering Home. Announcing into a screen nobody sees would burn the alert — it would be
    /// marked announced without having been seen.
    static func canAnnounce(sceneActive: Bool, settingsSheetPresented: Bool) -> Bool {
        sceneActive && !settingsSheetPresented
    }

    /// Shows `message`, waits out `duration`, then marks the alerts announced — after, not before, as
    /// Android does: if the wait is cancelled (the scene went inactive or the Settings sheet opened
    /// mid-toast), nothing is marked, so the alert stays pending and is shown again once announcing
    /// is allowed.
    @MainActor
    static func announce(
        _ message: String,
        for duration: Duration = duration,
        show: (String) -> Void,
        markSurfaced: () -> Void
    ) async {
        show(message)
        try? await Task.sleep(for: duration)
        guard !Task.isCancelled else { return }
        markSurfaced()
    }
}

/// Which alerts VoiceOver has already been told about, keyed by `AlertKey` (`AppNotification.alertKey()`).
///
/// Kept apart from `NotificationAlerts`' "announced" bookkeeping on purpose: an alert whose toast was cut
/// short (the scene went inactive, or the Settings sheet opened) stays pending, so its toast is shown again
/// once announcing is allowed — but its text was already spoken, so speaking it again would only repeat it.
/// Held by `HomeView` for its lifetime, so it outlives the `.task` that restarts whenever announcing becomes
/// allowed or stops being allowed. Never pruned, like `NotificationAlerts`' own set: it holds only the
/// handful of distinct alerts a session produces.
@MainActor
final class SpokenAlerts {
    private var keys: Set<AnyHashable> = []

    /// Records `key` as spoken and returns whether this is the first time it was seen.
    func markSpoken(_ key: AnyHashable) -> Bool {
        keys.insert(key).inserted
    }
}
