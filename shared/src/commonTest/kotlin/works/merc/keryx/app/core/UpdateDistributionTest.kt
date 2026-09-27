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
    fun unknownOrSideloadedInstallersKeepTheSelfUpdateCheckEnabled() {
        assertTrue(isSelfUpdateCheckSupported(null))
        assertTrue(isSelfUpdateCheckSupported("com.example.somesideloader"))
        assertTrue(isSelfUpdateCheckSupported(""))
    }
}
