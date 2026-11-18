package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.core.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NextcloudServerDialogContentTest {

    @Test
    fun showsTheScopeNoticeAndTheFailureReason() = runDesktopComposeUiTest {
        setContent {
            Box(Modifier.width(420.dp)) {
                NextcloudServerDialogContent(
                    url = "http://cloud.example.com",
                    onUrlChange = {},
                    checking = false,
                    error = ErrorKind.NEXTCLOUD_HTTPS_REQUIRED,
                    onSubmit = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("デバイスとセッション", substring = true).assertIsDisplayed()
        onNodeWithText("サーバーのアドレスは HTTPS である必要があります", substring = true).assertIsDisplayed()
    }

    @Test
    fun showsTheCheckWhileItRunsAndForwardsTyping() = runDesktopComposeUiTest {
        var typed = ""
        setContent {
            Box(Modifier.width(420.dp)) {
                NextcloudServerDialogContent(
                    url = "",
                    onUrlChange = { typed = it },
                    checking = true,
                    error = null,
                    onSubmit = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("サーバーを確認しています…").assertIsDisplayed()
        onNode(hasSetTextAction()).performTextInput("cloud.example.com")
        assertEquals("cloud.example.com", typed)
    }
}
