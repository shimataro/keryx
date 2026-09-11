package works.merc.keryx.app.core

/** Desktop-only constants (subprocess timeouts for OS integrations this platform alone shells out to). */

// --- Keychain (macOS) ---
/**
 * Bounds a single `/usr/bin/security` CLI invocation so a stuck/unanswered Keychain-access
 * dialog can't hang the connect flow forever; long enough for a real interactive prompt.
 */
const val KEYCHAIN_COMMAND_TIMEOUT_MS = 15_000L

// --- Linux URI scheme registration ---
/**
 * Bounds the best-effort `update-desktop-database` refresh so a stuck/slow process can't hang
 * app startup (registration runs synchronously before the window is created).
 */
const val UPDATE_DESKTOP_DATABASE_TIMEOUT_MS = 5_000L

// --- Windows URI scheme registration ---
/**
 * Bounds a single `reg.exe` invocation so a stuck/slow process can't hang app startup
 * (registerWindowsUriScheme runs three of these synchronously before the window is created).
 */
const val REG_EXE_TIMEOUT_MS = 5_000L
