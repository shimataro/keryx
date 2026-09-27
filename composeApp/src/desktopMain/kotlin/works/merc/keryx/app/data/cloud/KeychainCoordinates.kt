package works.merc.keryx.app.data.cloud

/**
 * Shared Keychain service name for the stored cloud tokens, used on Windows/Linux by
 * [KeyringTokenStorage] (java-keyring) and, on macOS, as [SecurityCliTokenStorage]'s
 * *legacy* service — see [KEYCHAIN_SERVICE_MACOS] for why macOS itself no longer writes
 * new items under this name. The **account** is per-provider (derived from
 * [works.merc.keryx.app.core.CloudStorageType.id]) and passed in per instance, so
 * Dropbox and Google Drive tokens live under distinct accounts of this one service.
 */
internal const val KEYCHAIN_SERVICE: String = "works.merc.keryx"

/**
 * The Compose desktop build's own Keychain service name on macOS, distinct from the native
 * SwiftUI app's `works.merc.keryx` (`shared/src/appleMain/.../KeychainTokenStorage.kt`). The two
 * apps share a bundle ID and may share `keryx.db`, but never a Keychain item: whichever one
 * disconnects a provider also revokes its refresh token, which would silently break the other
 * app's sync if both wrote to the same service/account (see "Distribution and coexistence" in
 * `docs/app-architecture.md`). `SecurityCliTokenStorage` migrates an existing item from
 * [KEYCHAIN_SERVICE] (this build's own name before this split) to this one the first time it is
 * read — see its class doc.
 */
internal const val KEYCHAIN_SERVICE_MACOS: String = "works.merc.keryx.compose"
