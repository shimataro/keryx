package works.merc.keryx.app.di

import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.data.cloud.KeyringTokenStorage
import works.merc.keryx.app.data.cloud.LibSecretTokenStorage
import works.merc.keryx.app.data.cloud.SecurityCliTokenStorage
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Pins [providerTokenStorage]'s three-way branch directly, rather than only through the two
 * top-level flags it is normally driven by ([works.merc.keryx.app.platform.isMacOs]/
 * [works.merc.keryx.app.platform.isSnap]) — those read real OS/environment state and can't be
 * flipped from a test. Without this, the branch that keeps a Store-installed user off the
 * plaintext fallback (`snap -> LibSecretTokenStorage`) could silently regress (removed, reordered
 * behind `macOs`, or driven by the wrong environment variable) with no test catching it.
 */
class PlatformModuleDesktopTest {
    @Test
    fun macOsAlwaysUsesSecurityCliRegardlessOfSnap() {
        assertIs<SecurityCliTokenStorage>(providerTokenStorage(CloudStorageType.DROPBOX, macOs = true, snap = false))
        assertIs<SecurityCliTokenStorage>(providerTokenStorage(CloudStorageType.DROPBOX, macOs = true, snap = true))
    }

    @Test
    fun snapUsesLibSecretWhenNotMacOs() {
        assertIs<LibSecretTokenStorage>(providerTokenStorage(CloudStorageType.DROPBOX, macOs = false, snap = true))
    }

    @Test
    fun defaultsToKeyringOutsideMacOsAndSnap() {
        assertIs<KeyringTokenStorage>(providerTokenStorage(CloudStorageType.DROPBOX, macOs = false, snap = false))
    }
}
