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

    /// The session showing a sign-in page that calls nothing back (Nextcloud's Login Flow v2 — see
    /// `openLoginPage(url:)`); separate from `activeSession` so closing one never touches the other.
    private var loginPageSession: ASWebAuthenticationSession?

    /// Bumped each time `presentLoginPage` shows a page, so the completion of a page it has since
    /// replaced (its `cancel()` completes asynchronously) does not clear the newer session.
    /// Main-actor state, like `loginPageSession`.
    private var loginPageGeneration = 0

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
            self.loginPageSession?.cancel()
            self.loginPageSession = nil
        }
    }

    /// Shows a sign-in page that sends nothing back to the app — wired as `KeryxSdk.start`'s
    /// `openLoginPage`. Nextcloud's Login Flow v2 learns of the sign-in by polling its server, so the
    /// session has no callback scheme and only ever ends by `closeLoginPage()` (or the user
    /// dismissing it). Called from any thread, like `open(url:callbackScheme:)`.
    func openLoginPage(url: String) {
        Task { @MainActor in
            self.presentLoginPage(url: url)
        }
    }

    /// Dismisses what `openLoginPage(url:)` showed — wired as `KeryxSdk.start`'s `closeLoginPage`,
    /// which the Kotlin flow calls however the sign-in ends.
    func closeLoginPage() {
        Task { @MainActor in
            self.loginPageSession?.cancel()
            self.loginPageSession = nil
        }
    }

    @MainActor
    private func presentLoginPage(url: String) {
        guard let pageURL = URL(string: url) else { return }
        loginPageSession?.cancel()
        loginPageGeneration += 1
        let generation = loginPageGeneration
        // Same `@Sendable` reasoning as `presentSession`'s handler. Nothing to forward: the page has
        // no callback, and the Kotlin flow ends the wait itself (by polling, or by being cancelled).
        let session = ASWebAuthenticationSession(url: pageURL, callbackURLScheme: nil) { @Sendable [weak self] _, _ in
            Task { @MainActor in
                guard let self, self.loginPageGeneration == generation else { return }
                self.loginPageSession = nil
            }
        }
        session.presentationContextProvider = self
        loginPageSession = session
        session.start()
    }

    @MainActor
    private func presentSession(url: String, callbackScheme: String) {
        guard let authURL = URL(string: url) else { return }
        // `@Sendable` keeps this closure from inheriting `presentSession`'s `@MainActor` isolation:
        // AuthenticationServices invokes it on an arbitrary (XPC reply) queue, where Swift 6's runtime
        // isolation check on a main-actor closure would trap before the hop below is ever reached.
        let session = ASWebAuthenticationSession(url: authURL, callbackURLScheme: callbackScheme) { @Sendable [weak self] callbackURL, _ in
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
