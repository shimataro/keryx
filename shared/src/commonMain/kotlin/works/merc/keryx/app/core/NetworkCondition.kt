package works.merc.keryx.app.core

/**
 * What the OS says about the network this device is currently on, as far as automatic data use is
 * concerned (see [works.merc.keryx.app.domain.NetworkUsagePolicy]).
 *
 * @property metered The OS marks the current network as metered / expensive — a cellular
 *   connection, but also a Wi-Fi network the user marked as metered, or a tethered hotspot.
 * @property dataSaver The user has explicitly asked the OS to save data on this network (Android's
 *   Data Saver while on a metered network; Apple's Low Data Mode, which can be set per network).
 */
data class NetworkCondition(val metered: Boolean, val dataSaver: Boolean) {
    companion object {
        /** No restriction at all: an unmetered network, no network, or a platform with no such signal. */
        val UNRESTRICTED = NetworkCondition(metered = false, dataSaver = false)
    }
}
