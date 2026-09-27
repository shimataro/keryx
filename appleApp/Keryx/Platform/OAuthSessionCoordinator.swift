import AuthenticationServices
import KeryxShared
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// Drives cloud-provider OAuth through `ASWebAuthenticationSession`, wired as `KeryxSdk.start`'s
/// `openAuthorization` closure — see "Apple targets in `:shared`" in `docs/app-architecture.md`.
/// Kotlin calls `open(url:callbackScheme:)` as a synchronous, fire-and-forget closure from whatever
/// dispatcher the connect flow happens to run on, so this hops to `@MainActor` internally (session
/// presentation is main-thread-only) rather than being `@MainActor`-isolated itself, which would
/// force every caller through `await` that Kotlin's plain closure type has no way to express.
///
/// `@unchecked Sendable`: genuinely only ever touched on the main thread in practice (every access
/// below is inside a `Task { @MainActor in … }` hop), same rationale as
/// `KotlinSendableBridging.swift`'s conformances.
final class OAuthSessionCoordinator: NSObject, @unchecked Sendable {
    /// Set once by `AppModel` right after `KeryxSdk.companion.start(...)` returns — the coordinator
    /// itself is constructed (and captured into the `openAuthorization` closure passed to `start`)
    /// before the `KeryxSdk` instance that call produces exists yet, so this can't be an `init` param.
    weak var sdk: KeryxSdk?

    private var activeSession: ASWebAuthenticationSession?

    func open(url: String, callbackScheme: String) {
        Task { @MainActor in
            self.presentSession(url: url, callbackScheme: callbackScheme)
        }
    }

    /// Dismisses the in-flight session, if any — call this alongside the connect flow's own
    /// `cancelConnect()` (the two are independent systems: cancelling the Kotlin coroutine does not
    /// by itself dismiss the system-presented browser sheet).
    func cancelActiveSession() {
        Task { @MainActor in
            self.activeSession?.cancel()
            self.activeSession = nil
        }
    }

    @MainActor
    private func presentSession(url: String, callbackScheme: String) {
        guard let authURL = URL(string: url) else { return }
        let session = ASWebAuthenticationSession(url: authURL, callbackURLScheme: callbackScheme) { [weak self] callbackURL, _ in
            Task { @MainActor in
                self?.activeSession = nil
                if let callbackURL {
                    _ = self?.sdk?.handleOAuthRedirect(url: callbackURL.absoluteString)
                }
                // No callback URL: the user cancelled from the system sheet's own UI, or the
                // request failed outright. Either way there is nothing to forward — the Kotlin
                // side's own `cancelConnect()` (driven by the app's own Cancel button) is the
                // other way this same wait ends, and neither path needs a distinct signal here.
            }
        }
        session.presentationContextProvider = self
        activeSession = session
        session.start()
    }
}

extension OAuthSessionCoordinator: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        #if os(macOS)
        return NSApplication.shared.keyWindow ?? NSApplication.shared.windows.first ?? ASPresentationAnchor()
        #else
        return UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first ?? ASPresentationAnchor()
        #endif
    }
}
