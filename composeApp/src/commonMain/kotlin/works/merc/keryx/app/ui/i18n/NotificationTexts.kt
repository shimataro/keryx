package works.merc.keryx.app.ui.i18n

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.InfoDialogText
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.feed_gone_message
import works.merc.keryx.app.resources.feed_url_changed
import works.merc.keryx.app.resources.notification_app_translocated
import works.merc.keryx.app.resources.notification_app_translocated_detail
import works.merc.keryx.app.resources.notification_token_storage_fallback
import works.merc.keryx.app.resources.notification_token_storage_fallback_detail
import works.merc.keryx.app.resources.notification_token_storage_not_persisted
import works.merc.keryx.app.resources.notification_token_storage_not_persisted_detail
import works.merc.keryx.app.resources.settings_import_failed
import works.merc.keryx.app.resources.settings_import_success
import works.merc.keryx.app.resources.update_available_notification
import works.merc.keryx.app.resources.update_ready_notification

/*
 * Localizes the notification data :shared emits (NotificationText / InfoDialogText) through this
 * app's Compose Resources. Two entry points over one mapping: a @Composable one for text rendered
 * in composition, and a suspend one for text built outside it (the Android alert Snackbar).
 */

/** [text] as a localized string, for rendering in composition. */
@Composable
fun notificationText(text: NotificationText): String {
    val (resource, args) = stringResourceOf(text)
    return stringResource(resource, *args)
}

/** [text] as a localized string, for use outside composition. */
suspend fun resolveNotificationText(text: NotificationText): String {
    val (resource, args) = stringResourceOf(text)
    return getString(resource, *args)
}

/** The explanatory dialog body [detail] names, localized. */
@Composable
fun infoDialogText(detail: InfoDialogText): String = stringResource(
    when (detail) {
        InfoDialogText.TOKEN_STORAGE_FALLBACK -> Res.string.notification_token_storage_fallback_detail
        InfoDialogText.TOKEN_STORAGE_NOT_PERSISTED -> Res.string.notification_token_storage_not_persisted_detail
        InfoDialogText.APP_TRANSLOCATED -> Res.string.notification_app_translocated_detail
    },
)

/**
 * The "N imported" text, with a " / N failed" suffix appended when [failed] is non-zero — the
 * settings Data tab's inline OPML import result (every import route lands there).
 */
internal suspend fun opmlImportedText(added: Int, failed: Int): String {
    val addedText = getPluralString(Res.plurals.settings_import_success, added, added)
    return if (failed > 0) {
        "$addedText / ${getPluralString(Res.plurals.settings_import_failed, failed, failed)}"
    } else {
        addedText
    }
}

/** The string resource (and its format arguments) for [text]. */
private fun stringResourceOf(text: NotificationText): Pair<StringResource, Array<Any>> = when (text) {
    is NotificationText.FeedGone -> Res.string.feed_gone_message to arrayOf(text.feedTitle)
    is NotificationText.FeedUrlChanged -> Res.string.feed_url_changed to arrayOf(text.feedTitle)
    is NotificationText.SyncFailed -> errorMessageResource(text.reason) to emptyArray()
    is NotificationText.UpdateAvailable -> Res.string.update_available_notification to arrayOf(text.version)
    is NotificationText.UpdateReadyToInstall -> Res.string.update_ready_notification to arrayOf(text.version)
    NotificationText.TokenStorageFallback -> Res.string.notification_token_storage_fallback to emptyArray()
    NotificationText.TokenStorageNotPersisted -> Res.string.notification_token_storage_not_persisted to emptyArray()
    NotificationText.AppTranslocated -> Res.string.notification_app_translocated to emptyArray()
}
