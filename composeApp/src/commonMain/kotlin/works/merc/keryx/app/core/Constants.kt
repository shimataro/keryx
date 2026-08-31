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
const val MILLIS_PER_HOUR = 60 * 60 * 1000L
const val MILLIS_PER_MINUTE = 60_000L
const val MILLIS_PER_SECOND = 1_000L

/**
 * Legacy (pre-compression) cloud file path/name for the synced DB. No longer written — every
 * upload/create goes to [CLOUD_DB_GZ_PATH] — but still read as a one-time fallback when the
 * compressed file does not exist yet remotely (a cloud this device has not synced to since
 * compression was added). See "Compressed Upload / Legacy Fallback" in sync-architecture.md.
 *
 * This fallback is a deliberately temporary bridge for the 0.x pre-release period. It is planned
 * for removal once the app reaches its v1.0.0 release, at which point every device still running
 * an older, [CLOUD_DB_GZ_PATH]-unaware build is expected to have upgraded; see the same doc
 * section for the removal note.
 */
const val CLOUD_DB_PATH = "/keryx.db"

/**
 * Primary cloud file path/name for the synced DB. Always gzip-compressed (`platform/Gzip`) — the
 * only path this app ever writes to. [CLOUD_DB_PATH] is read-only fallback for a cloud that has
 * not been written to since compression was added.
 */
const val CLOUD_DB_GZ_PATH = "/keryx.db.gz"

/**
 * Prefix/suffix of the archive a cloud-data reset creates, e.g.
 * `/keryx-20260811-103000.db.gz.bak` (see `cloudBackupPath`). Deliberately *not* derived from
 * [CLOUD_DB_GZ_PATH]: the archive name must not match Google Drive's `name = 'keryx.db.gz'`
 * lookup or OneDrive's basename addressing, or `CloudStorage.metadata(CLOUD_DB_GZ_PATH)` would end
 * up seeing the archive too.
 */
const val CLOUD_DB_BACKUP_PREFIX = "/keryx-"
const val CLOUD_DB_BACKUP_SUFFIX = ".db.gz.bak"

// --- Sync / network ---
/**
 * Upper bound on the size of the sync database, both the raw download (CloudFileTransfer)
 * and the decompressed gzip output (Gzip.decompressFile). A real 3,671-article snapshot
 * measures 21.4 MB uncompressed (see sync-architecture.md); this leaves generous headroom for
 * legitimate growth while still capping how much disk a corrupt or hostile cloud file — via a
 * small gzip payload with a very high expansion ratio, or simply an oversized raw file — can
 * consume before the SQLite-header check or the merge ever inspects it.
 */
const val MAX_SYNC_DB_SIZE_BYTES = 1024L * 1024 * 1024 // 1 GiB

const val SYNC_MAX_RETRY = 3
const val FEED_TIMEOUT_RETRY_COUNT = 1
const val SYNC_DEBOUNCE_MS = 5_000L
const val CONNECTION_TIMEOUT_MS = 10_000L
const val READ_TIMEOUT_SECONDS_DEFAULT = 30
const val MAX_REDIRECTS = 5 // redirect loop guard

/** HTTP request timeout for the shared client (distinct from [CONNECTION_TIMEOUT_MS], the connect phase). */
const val REQUEST_TIMEOUT_MS = 60_000L

/** Clock-skew tolerance for [works.merc.keryx.app.data.cloud.OAuthTokens.isExpired]'s default. */
const val TOKEN_EXPIRY_SKEW_MS = 60_000L

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

/**
 * Hex SHA-256 of the snapshot most recently uploaded to the cloud. A freshly built snapshot with
 * the same digest carries no change this device has not already published, so the upload is
 * skipped. Device-local (`sync_state` is excluded from the uploaded snapshot).
 */
const val SYNC_STATE_LAST_UPLOADED_DIGEST = "last_uploaded_snapshot_digest"

// --- Window / pane sizing (desktop) ---
const val WINDOW_DEFAULT_WIDTH = 1280
const val WINDOW_DEFAULT_HEIGHT = 800
const val WINDOW_MIN_HEIGHT = 400
const val FEED_LIST_PANE_WIDTH_DEFAULT = 260
const val ARTICLE_LIST_PANE_WIDTH_DEFAULT = 360
const val FEED_LIST_PANE_MIN_WIDTH = 180
const val FEED_LIST_PANE_MAX_WIDTH = 480
const val ARTICLE_LIST_PANE_MIN_WIDTH = 240
const val ARTICLE_LIST_PANE_MAX_WIDTH = 600
const val DETAIL_PANE_MIN_WIDTH = 280

/** Width of a [works.merc.keryx.app.ui.home.ResizableDivider] between two panes. */
const val PANE_DIVIDER_WIDTH = 8

/**
 * Minimum width at which all three home panes (feed list / article list / article detail) fit
 * side by side — the sum of each pane's own minimum width plus one divider between each pair.
 * [works.merc.keryx.app.ui.home.paneLayoutFor] resolves to
 * [works.merc.keryx.app.ui.home.PaneLayout.Triple] at or above this width, [DUAL_PANE_MIN_WIDTH]
 * up to it, and [works.merc.keryx.app.ui.home.PaneLayout.Single] below that.
 *
 * [WINDOW_MIN_WIDTH] is deliberately `>=` this value: the article reader's WebView must stay
 * composed for the pane's whole lifetime (see `ArticleDetailPane`'s KDoc and `known-issues.md`),
 * so the desktop window — which can never narrow below [WINDOW_MIN_WIDTH] — must never resolve to
 * anything but [works.merc.keryx.app.ui.home.PaneLayout.Triple]. `HomePaneLayoutTest`
 * pins this at [WINDOW_MIN_WIDTH].
 */
const val TRIPLE_PANE_MIN_WIDTH =
    FEED_LIST_PANE_MIN_WIDTH + ARTICLE_LIST_PANE_MIN_WIDTH + DETAIL_PANE_MIN_WIDTH + PANE_DIVIDER_WIDTH * 2

/** Minimum width at which the article list and article detail panes fit side by side (see
 * [TRIPLE_PANE_MIN_WIDTH]). */
const val DUAL_PANE_MIN_WIDTH = ARTICLE_LIST_PANE_MIN_WIDTH + DETAIL_PANE_MIN_WIDTH + PANE_DIVIDER_WIDTH

/** Must be `>= TRIPLE_PANE_MIN_WIDTH` — see [TRIPLE_PANE_MIN_WIDTH]'s KDoc. */
const val WINDOW_MIN_WIDTH = 720

/** Debounce for re-running search as the user types, so every keystroke doesn't trigger an FTS query. */
const val SEARCH_DEBOUNCE_MS = 250L

/** Debounce for persisting a pane's width to disk while it's being dragged. */
const val PANE_WIDTH_PERSIST_DEBOUNCE_MS = 500L

// How long after a new-article notification is sent onTrayAction still treats a click as an
// activation rather than a deliberate hide - see tray/TrayActionPolicy.kt.
const val TRAY_ACTION_NOTIFICATION_RECENCY_MS = 5_000L

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
// The tenant segment MUST stay `consumers`, not `common`. The Azure app registration is a
// "Personal Microsoft accounts only" one (see docs/build.md), i.e. signInAudience = Consumer, and
// Microsoft rejects that audience on `/common` outright: "The request is not valid for the
// application's 'userAudience' configuration. In order to use /common/ endpoint, the application
// must not be configured with 'Consumer' as the user audience." That rejection arrives only after
// the user has entered their address (the authorize page itself still renders), so it surfaces as
// a generic "authentication failed" rather than an obvious misconfiguration.
//
// Widening the registration to work/school accounts is not the fix: `Files.ReadWrite.AppFolder`
// (ONEDRIVE_SCOPES below) is a personal-account-only Graph permission, so an organizational
// account would force a far broader scope such as Files.ReadWrite(.All) over the user's whole
// drive — against the privacy stance in docs/external-spec.md. OneDrive sync is therefore
// personal-Microsoft-account-only by design; see docs/sync-architecture.md.
const val ONEDRIVE_AUTHORIZE_ENDPOINT = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"
const val ONEDRIVE_TOKEN_ENDPOINT = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
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
