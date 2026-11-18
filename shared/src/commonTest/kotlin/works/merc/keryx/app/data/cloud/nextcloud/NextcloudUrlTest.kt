package works.merc.keryx.app.data.cloud.nextcloud

import works.merc.keryx.app.core.NextcloudServerException
import works.merc.keryx.app.core.NextcloudServerProblem
import works.merc.keryx.app.core.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NextcloudUrlTest {

    @Test
    fun normalizesAcceptedAddresses() {
        val cases = listOf(
            "cloud.example.com" to "https://cloud.example.com",
            "  cloud.example.com  " to "https://cloud.example.com",
            "https://cloud.example.com/" to "https://cloud.example.com",
            "HTTPS://Cloud.Example.COM" to "https://cloud.example.com",
            "https://cloud.example.com:8443" to "https://cloud.example.com:8443",
            "cloud.example.com:8443/" to "https://cloud.example.com:8443",
            "https://example.com/nextcloud" to "https://example.com/nextcloud",
            "https://example.com/nextcloud///" to "https://example.com/nextcloud",
            "https://example.com/nextcloud/index.php" to "https://example.com/nextcloud",
            "https://example.com/nextcloud/index.php/apps/files/" to "https://example.com/nextcloud",
            "https://cloud.example.com/index.php/login" to "https://cloud.example.com",
            "https://host.example.com/index.php-backup" to "https://host.example.com/index.php-backup",
            "https://host.example.com/status.phpx/index.php/apps" to "https://host.example.com/status.phpx",
            "https://cloud.example.com/login" to "https://cloud.example.com",
            "https://cloud.example.com/remote.php/dav/files/alice/" to "https://cloud.example.com",
            "https://cloud.example.com/status.php" to "https://cloud.example.com",
            "192.168.1.10" to "https://192.168.1.10",
            "https://[fd00::1]:443/" to "https://[fd00::1]:443",
        )
        for ((input, expected) in cases) {
            val result = normalizeNextcloudUrl(input)
            assertIs<Result.Ok<String>>(result, "input: $input")
            assertEquals(expected, result.value, "input: $input")
        }
    }

    @Test
    fun rejectsNonHttpsSchemesAsHttpsRequired() {
        for (input in listOf("http://cloud.example.com", "HTTP://cloud.example.com", "ftp://cloud.example.com", "webdav://x")) {
            assertProblem(NextcloudServerProblem.HTTPS_REQUIRED, input)
        }
    }

    @Test
    fun rejectsMalformedAddressesAsNotNextcloud() {
        val inputs = listOf(
            "",
            "   ",
            "https://",
            "https:///path",
            "https://alice:secret@cloud.example.com",
            "alice@cloud.example.com",
            "https://cloud.example.com/?redirect=x",
            "https://cloud.example.com/#files",
            "https://cloud example.com",
            "https://cloud.example.com:port",
            "https://cloud.example.com:123456",
        )
        for (input in inputs) assertProblem(NextcloudServerProblem.NOT_NEXTCLOUD, input)
    }

    private fun assertProblem(expected: NextcloudServerProblem, input: String) {
        val result = normalizeNextcloudUrl(input)
        assertIs<Result.Err>(result, "input: '$input'")
        val exception = assertIs<NextcloudServerException>(result.exception, "input: '$input'")
        assertEquals(expected, exception.problem, "input: '$input'")
    }
}
