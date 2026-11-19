package works.merc.keryx.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.InfoDialogText
import works.merc.keryx.app.core.LocalWriteFailureKind
import works.merc.keryx.app.core.NotificationText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalWriteFailureReporterTest {
    private val center = NotificationCenter()
    private var now = 1_000L
    private val reporter = LocalWriteFailureReporter(center, Clock { now })

    private fun busy() = SQLiteException("database is locked", SQLiteErrorCode.SQLITE_BUSY)

    @Test
    fun aReportedFailurePostsOneErrorNamingItsKindWithItsExplanation() {
        reporter.report(busy())

        val n = center.items.value.single()
        assertEquals(AppNotificationLevel.ERROR, n.level)
        assertEquals(NotificationText.LocalWriteFailed(LocalWriteFailureKind.BUSY), n.text)
        assertEquals(AppNotificationAction.ShowInfoDialog(InfoDialogText.LOCAL_WRITE_FAILED_BUSY), n.action)
        assertEquals(1_000L, n.timestampMillis)
    }

    @Test
    fun eachKindExplainsItself() {
        reporter.report(SQLiteException("full", SQLiteErrorCode.SQLITE_FULL))
        reporter.report(IllegalStateException("not SQLite"))

        assertEquals(
            listOf(
                NotificationText.LocalWriteFailed(LocalWriteFailureKind.OTHER) to
                    AppNotificationAction.ShowInfoDialog(InfoDialogText.LOCAL_WRITE_FAILED_OTHER),
                NotificationText.LocalWriteFailed(LocalWriteFailureKind.DISK_FULL) to
                    AppNotificationAction.ShowInfoDialog(InfoDialogText.LOCAL_WRITE_FAILED_DISK_FULL),
            ),
            center.items.value.map { it.text to it.action },
        )
    }

    @Test
    fun repeatedFailuresOfTheSameKindStayOneEntry() {
        reporter.report(busy())
        now = 2_000L
        reporter.report(SQLiteException("table is locked", SQLiteErrorCode.SQLITE_LOCKED))

        val n = center.items.value.single()
        assertEquals(NotificationText.LocalWriteFailed(LocalWriteFailureKind.BUSY), n.text)
        assertEquals(2_000L, n.timestampMillis)
    }

    @Test
    fun runLocalWriteReturnsTrueAndPostsNothingWhenTheWriteSucceeds() = runTest {
        var ran = false
        assertTrue(reporter.runLocalWrite { ran = true })
        assertTrue(ran)
        assertTrue(center.items.value.isEmpty())
    }

    @Test
    fun runLocalWriteReportsAFailureAndReturnsFalse() = runTest {
        assertFalse(reporter.runLocalWrite { throw busy() })
        assertEquals(NotificationText.LocalWriteFailed(LocalWriteFailureKind.BUSY), center.items.value.single().text)
    }

    @Test
    fun runLocalWriteLetsCancellationThroughUnreported() = runTest {
        assertFailsWith<CancellationException> { reporter.runLocalWrite { throw CancellationException("cancelled") } }
        assertTrue(center.items.value.isEmpty())
    }
}
