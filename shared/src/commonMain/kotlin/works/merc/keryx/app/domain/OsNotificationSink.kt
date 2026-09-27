package works.merc.keryx.app.domain

/**
 * Posts a new-article message to the OS's own notification surface. On desktop this is a no-op —
 * `main.kt` posts tray notifications itself by collecting [NewArticleNotifier.trayEvents] instead,
 * a channel that already existed before this interface did (see that flow's own KDoc for why
 * Android doesn't use it the same way). On Android, [platformModule][works.merc.keryx.app.di.platformModule]
 * binds this to a real `NotificationManagerCompat` poster.
 *
 * @param count The new-article count this notification represents (`0` when not meaningful, e.g. a
 * direct [NewArticleNotifier.notify] call with no count of its own). Android's binding forwards
 * this to `NotificationCompat.Builder.setNumber`, which is *not* the same thing as the icon-level
 * "badge" a desktop OS shows — Android has no API to set an app-icon badge count independent of an
 * active notification (see `AndroidNotificationSink`'s own KDoc). On launchers that support it,
 * `setNumber` may be shown as the active notification's badge count; it also affects the count
 * shown in the icon's long-press menu — but it never creates an independent, persistent app-icon
 * badge the way desktop's `IconBadge.kt` does.
 */
fun interface OsNotificationSink {
    suspend fun post(message: String, count: Int)
}
