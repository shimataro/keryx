package works.merc.keryx.app.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import works.merc.keryx.app.core.AppInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun licensesUrlPointsAtTheDefaultBranchNotAHardcodedName() {
        // The repository's default branch is not necessarily named "master" (this project's is
        // "v0"), so a hardcoded branch name in the link would 404 once it diverges. "HEAD" always
        // resolves to whatever branch GitHub currently treats as default.
        assertFalse(LICENSES_URL.contains("/master/"), "LICENSES_URL must not hardcode a branch name: $LICENSES_URL")
        assertTrue(LICENSES_URL.startsWith("$PROJECT_URL/blob/HEAD/"), "LICENSES_URL must resolve against HEAD: $LICENSES_URL")
    }
}
