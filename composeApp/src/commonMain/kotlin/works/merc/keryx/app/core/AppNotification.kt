package works.merc.keryx.app.core

enum class AppNotificationLevel { INFO, WARNING, ERROR }

/** An actionable button a notification can offer (session-only, resolved by the UI). */
enum class AppNotificationAction { RESET_CLOUD_DATA }

/**
 * An in-session notification shown in the notification center (bell icon).
 * Session-only — never persisted. Warnings/errors plus INFO notices (e.g. new
 * articles from a background refresh). [action], when non-null, surfaces an
 * inline action button (e.g. "reset cloud data" for an unusable cloud DB).
 */
data class AppNotification(
    val id: String,
    val level: AppNotificationLevel,
    val message: String,
    val timestampMillis: Long,
    val action: AppNotificationAction? = null,
)
