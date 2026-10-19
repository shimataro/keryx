package works.merc.keryx.app.domain

import works.merc.keryx.app.core.NetworkCondition
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkUsagePolicyTest {

    private val unrestricted = NetworkCondition(metered = false, dataSaver = false)
    private val metered = NetworkCondition(metered = true, dataSaver = false)
    private val meteredDataSaver = NetworkCondition(metered = true, dataSaver = true)

    // iOS Low Data Mode can be set on a Wi-Fi network, so data saving without metering is real there.
    private val unmeteredDataSaver = NetworkCondition(metered = false, dataSaver = true)

    @Test
    fun automaticSyncRunsOnAnUnrestrictedNetworkWhateverTheSetting() {
        assertEquals(true, NetworkUsagePolicy.automaticSyncAllowed(unrestricted, syncOnMetered = false))
        assertEquals(true, NetworkUsagePolicy.automaticSyncAllowed(unrestricted, syncOnMetered = true))
    }

    @Test
    fun automaticSyncOnAMeteredNetworkFollowsTheSetting() {
        assertEquals(false, NetworkUsagePolicy.automaticSyncAllowed(metered, syncOnMetered = false))
        assertEquals(true, NetworkUsagePolicy.automaticSyncAllowed(metered, syncOnMetered = true))
    }

    @Test
    fun automaticSyncNeverRunsWhileDataSaverIsOn() {
        for (condition in listOf(meteredDataSaver, unmeteredDataSaver)) {
            assertEquals(false, NetworkUsagePolicy.automaticSyncAllowed(condition, syncOnMetered = false))
            assertEquals(false, NetworkUsagePolicy.automaticSyncAllowed(condition, syncOnMetered = true))
        }
    }

    @Test
    fun automaticRefreshStopsOnlyForDataSaver() {
        assertEquals(true, NetworkUsagePolicy.automaticRefreshAllowed(unrestricted))
        assertEquals(true, NetworkUsagePolicy.automaticRefreshAllowed(metered))
        assertEquals(false, NetworkUsagePolicy.automaticRefreshAllowed(meteredDataSaver))
        assertEquals(false, NetworkUsagePolicy.automaticRefreshAllowed(unmeteredDataSaver))
    }

    @Test
    fun unrestrictedConstantIsNeitherMeteredNorSaving() {
        assertEquals(unrestricted, NetworkCondition.UNRESTRICTED)
    }
}
