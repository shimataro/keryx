package works.merc.keryx.app.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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

    @Test
    fun syncGateFlowFollowsBothTheNetworkAndTheOptIn() = runTest {
        val condition = MutableStateFlow(metered)
        val optIn = MutableStateFlow(false)
        val gate = NetworkUsagePolicy.automaticSyncAllowedFlow(condition, optIn)

        assertEquals(false, gate.first())
        optIn.value = true
        assertEquals(true, gate.first())
        condition.value = meteredDataSaver
        assertEquals(false, gate.first())
        condition.value = unrestricted
        assertEquals(true, gate.first())
    }

    @Test
    fun conditionFromAnUnmeteredNetworkIgnoresTheDataSaverSwitch() {
        assertEquals(NetworkCondition.UNRESTRICTED, NetworkCondition.of(unmetered = true, dataSaverRequested = true))
        assertEquals(NetworkCondition.UNRESTRICTED, NetworkCondition.of(unmetered = true, dataSaverRequested = false))
    }

    @Test
    fun conditionFromAMeteredNetworkCarriesTheDataSaverSwitch() {
        assertEquals(metered, NetworkCondition.of(unmetered = false, dataSaverRequested = false))
        assertEquals(meteredDataSaver, NetworkCondition.of(unmetered = false, dataSaverRequested = true))
    }
}
