package works.merc.keryx.app.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create
import works.merc.keryx.app.core.NetworkCondition

actual fun platformNetworkConditionMonitor(): NetworkConditionMonitor = IosNetworkConditionMonitor()

/**
 * Follows the current network path through `nw_path_monitor`: `isExpensive` (cellular, or a
 * tethered hotspot) is [NetworkCondition.metered], and `isConstrained` (Low Data Mode, which the
 * user sets per network — Wi-Fi included) is [NetworkCondition.dataSaver].
 *
 * Nothing is emitted until the monitor delivers its first path — which it does right after
 * starting — so a collector never acts on a guessed value. A path with no usable network counts as
 * unrestricted, the same as on Android.
 */
internal class IosNetworkConditionMonitor : NetworkConditionMonitor {
    private val state = MutableStateFlow<NetworkCondition?>(null)

    // Held for the life of the process (this is a singleton), so the monitor keeps running.
    private val monitor = nw_path_monitor_create()

    override val condition: Flow<NetworkCondition> = state.filterNotNull()
    override val supported: Boolean = true

    init {
        nw_path_monitor_set_update_handler(monitor) { path ->
            state.value = if (nw_path_get_status(path) != nw_path_status_satisfied) {
                NetworkCondition.UNRESTRICTED
            } else {
                NetworkCondition(metered = nw_path_is_expensive(path), dataSaver = nw_path_is_constrained(path))
            }
        }
        nw_path_monitor_set_queue(monitor, dispatch_queue_create("works.merc.keryx.network-condition", null))
        nw_path_monitor_start(monitor)
    }
}
