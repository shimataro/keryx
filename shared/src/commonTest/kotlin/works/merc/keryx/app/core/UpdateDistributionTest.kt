package works.merc.keryx.app.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateDistributionTest {

    @Test
    fun playStoreInstallersDisableTheSelfUpdateCheck() {
        assertFalse(isSelfUpdateCheckSupported("com.android.vending"))
        assertFalse(isSelfUpdateCheckSupported("com.google.android.feedback"))
    }

    @Test
    fun fDroidClientsDisableTheSelfUpdateCheck() {
        listOf(
            "org.fdroid.fdroid",
            "org.fdroid.basic",
            "org.fdroid.fdroid.privileged",
            "com.looker.droidify",
            "com.machiav3lli.fdroid",
        ).forEach { installer ->
            assertFalse(isSelfUpdateCheckSupported(installer), installer)
        }
    }

    @Test
    fun aBuildThatOptsOutDisablesTheSelfUpdateCheckWhateverTheInstaller() {
        assertFalse(isSelfUpdateCheckSupported(null, distributionAllowsSelfUpdate = false))
        assertFalse(isSelfUpdateCheckSupported("com.example.somesideloader", distributionAllowsSelfUpdate = false))
        assertFalse(isSelfUpdateCheckSupported("com.android.vending", distributionAllowsSelfUpdate = false))
    }

    @Test
    fun aBuildThatAllowsItStillDefersToTheInstaller() {
        assertTrue(isSelfUpdateCheckSupported(null, distributionAllowsSelfUpdate = true))
        assertFalse(isSelfUpdateCheckSupported("org.fdroid.fdroid", distributionAllowsSelfUpdate = true))
    }

    @Test
    fun unknownOrSideloadedInstallersKeepTheSelfUpdateCheckEnabled() {
        assertTrue(isSelfUpdateCheckSupported(null))
        assertTrue(isSelfUpdateCheckSupported("com.example.somesideloader"))
        assertTrue(isSelfUpdateCheckSupported(""))
    }
}
