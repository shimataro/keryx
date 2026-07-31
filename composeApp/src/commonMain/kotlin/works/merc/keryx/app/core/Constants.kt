package works.merc.keryx.app.core

/** App-wide constants. */

const val APP_NAME = "Keryx"

const val DB_FILE_NAME = "keryx.db"
const val LOCAL_SETTINGS_FILE_NAME = "local_settings.json"

/**
 * SQLite `busy_timeout`, applied everywhere the DB is opened (main driver, `DatabaseMerger`'s ATTACH
 * connection, `DatabaseSnapshot`'s `VACUUM INTO`). Lets a reader/writer wait out — rather than error
 * on `SQLITE_BUSY` against — the brief write lock held by an incremental FTS insert, a full index
 * rebuild, or a sync merge on another connection.
 */
const val SQLITE_BUSY_TIMEOUT_MS = 5_000L

// --- Time ---
const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

/** Cloud file path/name for the synced DB (leading slash for Dropbox; the basename for Google Drive). */
const val CLOUD_DB_PATH = "/keryx.db"

// --- Sync / network ---
const val SYNC_MAX_RETRY = 3
const val FEED_TIMEOUT_RETRY_COUNT = 1
const val SYNC_DEBOUNCE_MS = 5_000L
const val CONNECTION_TIMEOUT_MS = 10_000L
const val READ_TIMEOUT_SECONDS_DEFAULT = 30
const val MAX_REDIRECTS = 5 // redirect loop guard

/** How much of a cloud-storage HTTP error body to keep in a log/exception message. */
const val CLOUD_ERROR_BODY_PREVIEW_LENGTH = 200

// --- global_settings keys ---
const val SETTING_READ_TIMEOUT_SECONDS = "read_timeout_seconds"
const val SETTING_CACHE_RETENTION_DAYS = "cache_retention_days"
const val SETTING_ARTICLE_LIST_DEFAULT_UNREAD_ONLY = "article_list_default_unread_only"

const val CACHE_RETENTION_DAYS_DEFAULT = 30

// --- Feed health ---
/**
 * Internal marker written to `feeds.last_error` when a feed responds 410 Gone. A fixed sentinel (not
 * a user-facing message and not a raw exception text) so the UI can recognize the state and render
 * its own localized wording. 410 deliberately does not bump `error_count` (it is permanent, not a
 * retry candidate), so this column is the only signal a feed is gone.
 */
const val FEED_ERROR_REASON_GONE = "gone"

// --- Full-text search ---
/** Minimum search term length. The trigram tokenizer doesn't index terms under 3 characters, so shorter terms are excluded from search. */
const val SEARCH_MIN_TERM_LENGTH = 3

// --- sync_state keys ---
const val SYNC_STATE_LAST_SYNCED_AT = "last_synced_at"
const val SYNC_STATE_CLOUD_FILE_REV = "cloud_file_rev"

// --- Window / pane sizing (desktop) ---
const val WINDOW_DEFAULT_WIDTH = 1280
const val WINDOW_DEFAULT_HEIGHT = 800
const val WINDOW_MIN_WIDTH = 720 // must be >= sum of pane minimums (700) + divider slack
const val WINDOW_MIN_HEIGHT = 400
const val FEED_LIST_PANE_WIDTH_DEFAULT = 260
const val ARTICLE_LIST_PANE_WIDTH_DEFAULT = 360
const val FEED_LIST_PANE_MIN_WIDTH = 180
const val FEED_LIST_PANE_MAX_WIDTH = 480
const val ARTICLE_LIST_PANE_MIN_WIDTH = 240
const val ARTICLE_LIST_PANE_MAX_WIDTH = 600
const val DETAIL_PANE_MIN_WIDTH = 280

// --- Article scroll memory ---
const val MAX_REMEMBERED_SCROLL_POSITIONS = 5

// --- Dropbox OAuth endpoints ---
const val DROPBOX_AUTHORIZE_ENDPOINT = "https://www.dropbox.com/oauth2/authorize"
const val DROPBOX_TOKEN_ENDPOINT = "https://api.dropboxapi.com/oauth2/token"
const val DROPBOX_REVOKE_ENDPOINT = "https://api.dropboxapi.com/2/auth/token/revoke"

// --- Google (Drive) OAuth endpoints ---
const val GOOGLE_AUTHORIZE_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
const val GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
const val GOOGLE_REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"

/** Google Drive "application data" hidden folder scope — the closest analog to Dropbox's app folder. */
const val GOOGLE_DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

// --- OneDrive (Microsoft Identity platform / Graph) endpoints ---
// `common` tenant accepts both personal Microsoft accounts and work/school accounts.
const val ONEDRIVE_AUTHORIZE_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
const val ONEDRIVE_TOKEN_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
const val ONEDRIVE_GRAPH_BASE = "https://graph.microsoft.com/v1.0"

/**
 * OneDrive scopes. `Files.ReadWrite.AppFolder` grants access only to the app's hidden special
 * folder (`/me/drive/special/approot`) — the analog of Dropbox's app folder / Drive's appDataFolder.
 * `offline_access` is required to receive a refresh token. Microsoft has no standard OAuth token
 * revocation endpoint, so there is no revoke constant.
 */
const val ONEDRIVE_SCOPES = "Files.ReadWrite.AppFolder offline_access"

// --- OAuth connect flow ---
/** Custom URI redirect for providers that accept an arbitrary scheme (Dropbox). Google uses loopback. */
const val OAUTH_CUSTOM_URI_REDIRECT = "keryx://oauth2/callback"

/** How long the interactive connect flow waits for the browser redirect before timing out. */
const val OAUTH_CONNECT_TIMEOUT_MS = 3 * 60_000L

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
