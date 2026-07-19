package works.merc.keryx.app.core

enum class AppNotificationLevel { INFO, WARNING, ERROR }

/**
 * An in-session notification shown in the notification center (bell icon).
 * Session-only — never persisted. Warnings/errors plus INFO notices (e.g. new
 * articles from a background refresh).
 */
data class AppNotification(
    val id: String,
    val level: AppNotificationLevel,
    val message: String,
    val timestampMillis: Long,
)
