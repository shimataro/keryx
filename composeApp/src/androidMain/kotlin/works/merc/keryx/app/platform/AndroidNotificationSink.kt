package works.merc.keryx.app.platform

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.jetbrains.compose.resources.getString
import works.merc.keryx.app.R
import works.merc.keryx.app.core.APP_NAME
import works.merc.keryx.app.domain.OsNotificationSink
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.notification_channel_new_articles_description
import works.merc.keryx.app.resources.notification_channel_new_articles_name

/** Stable id for [NEW_ARTICLES_CHANNEL_ID], so a later post replaces the previous one instead of
 * stacking — the same "one slot, latest wins" behavior the desktop tray notifications have. */
private const val NEW_ARTICLES_NOTIFICATION_ID = 1

private const val NEW_ARTICLES_CHANNEL_ID = "new_articles"

/**
 * Posts new-article messages as real Android notifications.
 *
 * [NotificationManagerCompat.areNotificationsEnabled] covers both the API 33+ `POST_NOTIFICATIONS`
 * runtime permission and a user-level app/channel notification block in one call, so a single
 * guard is enough — without it, `notify()` would also trip a Lint `MissingPermission` warning.
 * The channel is (re)created on every post; [NotificationManagerCompat.createNotificationChannel]
 * is a no-op when a channel with the same id already exists, so this costs nothing beyond the
 * first call and needs no separate "have I created this yet" state.
 *
 * There is deliberately no attempt here to mirror desktop's *icon-level* unread badge
 * (`desktopMain/IconBadge.kt`'s `drawUnreadBadge`, a persistent digit composited onto the app icon
 * itself): Android's launcher notification dot is tied to the presence of an *active* notification
 * — `NotificationChannelCompat.setShowBadge` (left at its default `true` here) only turns that dot
 * on or off per channel, and `setNumber` below only affects the *active* notification's badge count
 * on launchers that support one, plus the count shown in the icon's long-press menu — never an
 * independent digit drawn on the icon itself. There is no public API to set an icon-level badge
 * count independent of an active notification (unlike iOS's `setApplicationIconBadgeNumber`), so
 * posting one persistent, undismissable notification just to keep a badge alive would fight the
 * platform's own notification model. See `background-update.md` for the full comparison against
 * desktop.
 */
class AndroidNotificationSink(private val context: Context) : OsNotificationSink {
    override suspend fun post(message: String, count: Int) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(NEW_ARTICLES_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(getString(Res.string.notification_channel_new_articles_name))
                .setDescription(getString(Res.string.notification_channel_new_articles_description))
                .build(),
        )

        // MainActivity lives in :androidApp, a module composeApp (this module) cannot depend on,
        // so it can't be referenced by class here — the launcher intent is resolved by the OS from
        // the manifest instead, exactly like BrowserOpener resolving a browser by action.
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val notification = NotificationCompat.Builder(context, NEW_ARTICLES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_keryx)
            .setContentTitle(APP_NAME)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            // Affects this notification's badge count on launchers that support one, plus the
            // icon's long-press menu — never an on-icon digit; see this class's own KDoc. A count
            // <= 0 (e.g. a direct notify() call with no count of its own) leaves the system's own
            // default (one notification = "1") rather than showing "0".
            .apply { if (count > 0) setNumber(count) }
            .build()

        // areNotificationsEnabled() above already covers the POST_NOTIFICATIONS permission check
        // this call would otherwise need guarded inline.
        @Suppress("MissingPermission")
        manager.notify(NEW_ARTICLES_NOTIFICATION_ID, notification)
    }
}
