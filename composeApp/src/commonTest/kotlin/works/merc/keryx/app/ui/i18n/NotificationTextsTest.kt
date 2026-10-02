package works.merc.keryx.app.ui.i18n

import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import works.merc.keryx.app.core.ErrorKind
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.error_cloud_auth
import works.merc.keryx.app.resources.error_cloud_data_incompatible
import works.merc.keryx.app.resources.error_cloud_storage
import works.merc.keryx.app.resources.error_generic
import works.merc.keryx.app.resources.error_schema_version
import works.merc.keryx.app.resources.error_sync_conflict
import works.merc.keryx.app.resources.feed_gone_message
import works.merc.keryx.app.resources.feed_url_changed
import works.merc.keryx.app.resources.notification_app_translocated
import works.merc.keryx.app.resources.notification_token_storage_fallback
import works.merc.keryx.app.resources.notification_token_storage_not_persisted
import works.merc.keryx.app.resources.settings_import_failed
import works.merc.keryx.app.resources.settings_import_success
import works.merc.keryx.app.resources.update_available_notification
import works.merc.keryx.app.resources.update_ready_notification
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [resolveNotificationText] must produce exactly the text the repositories used to build
 * themselves through `NotificationMessages` before notifications became data — i.e. the same
 * resource, with the same arguments, for every [NotificationText].
 */
class NotificationTextsTest {

    @Test
    fun feedNotificationsFormatTheFeedTitle() = runTest {
        assertEquals(getString(Res.string.feed_gone_message, "Blog"), resolveNotificationText(NotificationText.FeedGone("Blog")))
        assertEquals(getString(Res.string.feed_url_changed, "Blog"), resolveNotificationText(NotificationText.FeedUrlChanged("Blog")))
    }

    @Test
    fun syncFailedResolvesEachSyncErrorKindToItsOwnMessage() = runTest {
        for ((kind, resource) in listOf(
            ErrorKind.CLOUD_AUTH to Res.string.error_cloud_auth,
            ErrorKind.SCHEMA_VERSION to Res.string.error_schema_version,
            ErrorKind.CLOUD_DATA_INCOMPATIBLE to Res.string.error_cloud_data_incompatible,
            ErrorKind.SYNC_CONFLICT to Res.string.error_sync_conflict,
            ErrorKind.CLOUD_STORAGE to Res.string.error_cloud_storage,
            ErrorKind.GENERIC to Res.string.error_generic,
        )) {
            assertEquals(getString(resource), resolveNotificationText(NotificationText.SyncFailed(kind)), kind.name)
        }
    }

    @Test
    fun opmlImportedTextReportsOnlyTheAddedCountWhenNothingFailed() = runTest {
        val expected = getPluralString(Res.plurals.settings_import_success, 5, 5)
        assertEquals(expected, opmlImportedText(added = 5, failed = 0))
    }

    @Test
    fun opmlImportedTextAppendsTheFailedCountWhenSomeImportsFailed() = runTest {
        val addedText = getPluralString(Res.plurals.settings_import_success, 2, 2)
        val failedText = getPluralString(Res.plurals.settings_import_failed, 1, 1)
        assertEquals("$addedText / $failedText", opmlImportedText(added = 2, failed = 1))
    }

    @Test
    fun updateNotificationsFormatTheVersion() = runTest {
        assertEquals(getString(Res.string.update_available_notification, "2.0.0"), resolveNotificationText(NotificationText.UpdateAvailable("2.0.0")))
        assertEquals(getString(Res.string.update_ready_notification, "2.0.0"), resolveNotificationText(NotificationText.UpdateReadyToInstall("2.0.0")))
    }

    @Test
    fun fixedTextNotificationsResolveTheirBellRowString() = runTest {
        assertEquals(getString(Res.string.notification_token_storage_fallback), resolveNotificationText(NotificationText.TokenStorageFallback))
        assertEquals(getString(Res.string.notification_token_storage_not_persisted), resolveNotificationText(NotificationText.TokenStorageNotPersisted))
        assertEquals(getString(Res.string.notification_app_translocated), resolveNotificationText(NotificationText.AppTranslocated))
    }
}
