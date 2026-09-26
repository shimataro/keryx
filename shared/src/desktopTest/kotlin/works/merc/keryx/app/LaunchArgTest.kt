package works.merc.keryx.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LaunchArgTest {
    @Test
    fun classifiesAnOAuthCallbackUri() {
        val arg = classifyLaunchArg("keryx://oauth2/callback?code=abc&state=xyz")
        val callback = assertIs<LaunchArg.OAuthCallback>(arg)
        assertEquals("keryx://oauth2/callback?code=abc&state=xyz", callback.uri)
    }

    @Test
    fun classifiesAnOpmlFilePathRegardlessOfExtensionCase() {
        val lower = assertIs<LaunchArg.OpmlFile>(classifyLaunchArg("/home/user/subscriptions.opml"))
        assertEquals("/home/user/subscriptions.opml", lower.path)

        val upper = assertIs<LaunchArg.OpmlFile>(classifyLaunchArg("C:\\Users\\user\\Feeds.OPML"))
        assertEquals("C:\\Users\\user\\Feeds.OPML", upper.path)
    }

    @Test
    fun anUnrelatedArgumentClassifiesToNull() {
        assertNull(classifyLaunchArg("--some-unrelated-flag"))
        assertNull(classifyLaunchArg("/home/user/document.txt"))
    }
}
