package works.merc.keryx.app.domain

/**
 * Posts a new-article message to the OS's own notification surface. On desktop this is a no-op —
 * `main.kt` posts tray notifications itself by collecting [NewArticleNotifier.trayEvents] instead,
 * a channel that already existed before this interface did (see that flow's own KDoc for why
 * Android doesn't use it the same way). On Android, [platformModule][works.merc.keryx.app.di.platformModule]
 * binds this to a real `NotificationManagerCompat` poster.
 */
fun interface OsNotificationSink {
    suspend fun post(message: String)
}
