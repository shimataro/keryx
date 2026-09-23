package works.merc.keryx.app.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.AppInfo
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.contact_email
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun rendersPrivacyPolicyLink() = runDesktopComposeUiTest {
        setContent { AboutDialogContent() }
        waitForIdle()

        // The privacy policy link (settings_privacy_policy) opens the locale-specific
        // privacy_policy_url, required by Google Play's User Data policy ("a privacy policy
        // link or text within the app itself").
        onNodeWithText("プライバシーポリシー").assertIsDisplayed()
    }

    @Test
    fun rendersTermsLink() = runDesktopComposeUiTest {
        setContent { AboutDialogContent() }
        waitForIdle()

        // The terms of service link (settings_terms) opens the locale-specific terms_url.
        onNodeWithText("利用規約").assertIsDisplayed()
    }

    @Test
    fun rendersContactLink() = runDesktopComposeUiTest {
        var openedUrl: String? = null
        var expectedUrl: String? = null
        setContent {
            expectedUrl = mailtoUrl(stringResource(Res.string.contact_email))
            AboutDialogContent(openUrl = { openedUrl = it })
        }
        waitForIdle()

        // Clicking the contact link (settings_contact, labelled as email) opens a mailto: URL for
        // the locale-specific contact_email. The address itself is only a hover tooltip on desktop
        // (and not shown at all on touch), so the row is located by its label.
        onNodeWithText("お問い合わせ（メール）")
            .assertIsDisplayed()
            .performClick()
        waitForIdle()

        assertEquals(expectedUrl, openedUrl)
    }

    @Test
    fun groupsSupportLinksAboveLegalLinks() = runDesktopComposeUiTest {
        setContent { AboutDialogContent() }
        waitForIdle()

        // Product/support links come first, then the legal documents, in this exact order.
        val labels = listOf("ウェブサイト", "プロジェクトページ", "お問い合わせ（メール）", "利用規約", "プライバシーポリシー", "オープンソースライセンス")
        val tops = labels.map { onNodeWithText(it).getUnclippedBoundsInRoot().top }
        assertEquals(tops.sorted(), tops, "About links out of order: ${labels.zip(tops)}")
        assertEquals(tops.size, tops.toSet().size, "About links overlap: ${labels.zip(tops)}")
    }

    @Test
    fun linkRowSupportingHidesUrlOnlyOnTouchWhenRequested() {
        val url = "https://example.com/"
        // Desktop always keeps the URL (it is only a hover tooltip there).
        assertEquals(url, linkRowSupporting(url, showUrlInline = true, touchPrimary = false))
        assertEquals(url, linkRowSupporting(url, showUrlInline = false, touchPrimary = false))
        // A touch-primary platform shows it as a second line unless the caller opts out.
        assertEquals(url, linkRowSupporting(url, showUrlInline = true, touchPrimary = true))
        assertNull(linkRowSupporting(url, showUrlInline = false, touchPrimary = true))
    }

    @Test
    fun mailtoUrlPrefixesTheAddress() {
        assertEquals("mailto:keryx@example.com", mailtoUrl("keryx@example.com"))
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
