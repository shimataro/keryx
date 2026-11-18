package works.merc.keryx.app.ui.i18n

import works.merc.keryx.app.core.ErrorKind
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.error_nextcloud_https_required
import works.merc.keryx.app.resources.error_nextcloud_not_found
import works.merc.keryx.app.resources.error_nextcloud_unreachable
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorMessagesTest {

    @Test
    fun eachNextcloudServerProblemHasItsOwnMessage() {
        assertEquals(Res.string.error_nextcloud_https_required, errorMessageResource(ErrorKind.NEXTCLOUD_HTTPS_REQUIRED))
        assertEquals(Res.string.error_nextcloud_not_found, errorMessageResource(ErrorKind.NEXTCLOUD_NOT_FOUND))
        assertEquals(Res.string.error_nextcloud_unreachable, errorMessageResource(ErrorKind.NEXTCLOUD_UNREACHABLE))
    }

    @Test
    fun everyErrorKindMapsToADistinctMessage() {
        val resources = ErrorKind.entries.map(::errorMessageResource)
        assertEquals(resources.size, resources.toSet().size)
    }
}
