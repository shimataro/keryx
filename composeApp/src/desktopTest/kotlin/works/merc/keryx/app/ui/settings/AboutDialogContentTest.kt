package works.merc.keryx.app.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import works.merc.keryx.app.core.AppInfo
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AboutDialogContentTest {

    @Test
    fun rendersAppVersion() = runDesktopComposeUiTest {
        setContent { AboutDialogContent() }
        waitForIdle()

        // Version flows BuildConfig.VERSION -> AppInfo.version -> the "バージョン %s" string.
        onNodeWithText(AppInfo.version, substring = true).assertIsDisplayed()
    }

    @Test
    fun rendersWebsiteLink() = runDesktopComposeUiTest {
        setContent { AboutDialogContent() }
        waitForIdle()

        // The official website link (settings_website) opens the locale-specific website_url.
        onNodeWithText("ウェブサイト").assertIsDisplayed()
    }

    @Test
    fun rendersLicensesLink() = runDesktopComposeUiTest {
        setContent { AboutDialogContent() }
        waitForIdle()

        // The open-source licenses link (settings_licenses) opens THIRD-PARTY-LICENSES.md.
        onNodeWithText("オープンソースライセンス").assertIsDisplayed()
    }
}
