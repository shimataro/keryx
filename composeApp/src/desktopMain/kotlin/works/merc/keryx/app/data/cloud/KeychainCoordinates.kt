package works.merc.keryx.app.data.cloud

/**
 * Shared Keychain service name for the stored cloud tokens, used by both desktop
 * secure-store backends — [KeyringTokenStorage] (java-keyring, Windows/Linux) and
 * [SecurityCliTokenStorage] (macOS `/usr/bin/security`) — so the service name
 * never drifts apart between them. The **account** is per-provider (derived from
 * [works.merc.keryx.app.core.CloudStorageType.id]) and passed in per instance, so
 * Dropbox and Google Drive tokens live under distinct accounts of this one service.
 */
internal const val KEYCHAIN_SERVICE: String = "works.merc.keryx"
