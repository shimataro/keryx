package works.merc.keryx.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserLaunchKindTest {
    @Test
    fun httpAndHttpsOpenInAnInAppBrowserTab() {
        for (url in listOf(
            "https://example.com/article?id=1",
            "http://example.com/",
            "HTTPS://EXAMPLE.COM/",
            "  https://example.com/padded  ",
        )) {
            assertEquals(BrowserLaunchKind.IN_APP_BROWSER_TAB, browserLaunchKind(url), "url=$url")
        }
    }

    @Test
    fun mailtoOpensAMailComposer() {
        for (url in listOf("mailto:someone@example.com?subject=Hi", "MAILTO:someone@example.com")) {
            assertEquals(BrowserLaunchKind.MAIL_COMPOSE, browserLaunchKind(url), "url=$url")
        }
    }

    @Test
    fun everyOtherLinkKeepsThePlainViewLaunch() {
        for (url in listOf(
            "ftp://example.com/file",
            "market://details?id=x",
            "file:///etc/hosts",
            "//example.com/protocol-relative",
            "/relative/path",
            "example.com",
            "httpx://example.com",
            "https:/example.com",
            "",
        )) {
            assertEquals(BrowserLaunchKind.VIEW, browserLaunchKind(url), "url=$url")
        }
    }
}
