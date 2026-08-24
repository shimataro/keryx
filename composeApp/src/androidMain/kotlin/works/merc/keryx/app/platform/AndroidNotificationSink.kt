package works.merc.keryx.app.platform

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.jetbrains.compose.resources.getString
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
 */
class AndroidNotificationSink(private val context: Context) : OsNotificationSink {
    override suspend fun post(message: String) {
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
            // Placeholder system glyph — a proper monochrome status-bar icon belongs with the
            // rest of the Material iconography pass (KeryxIcons' Android variant), out of scope here.
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(APP_NAME)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        // areNotificationsEnabled() above already covers the POST_NOTIFICATIONS permission check
        // this call would otherwise need guarded inline.
        @Suppress("MissingPermission")
        manager.notify(NEW_ARTICLES_NOTIFICATION_ID, notification)
    }
}
