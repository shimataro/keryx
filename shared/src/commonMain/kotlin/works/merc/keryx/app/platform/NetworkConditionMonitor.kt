package works.merc.keryx.app.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import works.merc.keryx.app.core.NetworkCondition

/**
 * Watches whether the current network is metered and whether the OS's data-saving mode is on.
 * Obtained through [platformNetworkConditionMonitor]; an interface (rather than an `expect class`)
 * so tests can substitute a fake.
 */
interface NetworkConditionMonitor {
    /**
     * The current [NetworkCondition], then every change to it. A collector receives the current
     * value as soon as it is known — immediately on every platform except iOS, where it arrives
     * with the system's first path update.
     */
    val condition: Flow<NetworkCondition>

    /**
     * Whether this platform reports metered networks at all. When false, [condition] is always
     * [NetworkCondition.UNRESTRICTED] and the metered-network setting is not offered.
     */
    val supported: Boolean
}

/** A platform with no metered-network signal (desktop, macOS): never restricted. */
object UnrestrictedNetworkConditionMonitor : NetworkConditionMonitor {
    override val condition: Flow<NetworkCondition> = flowOf(NetworkCondition.UNRESTRICTED)
    override val supported: Boolean = false
}

/** This platform's [NetworkConditionMonitor]. Called once, by the Koin graph. */
expect fun platformNetworkConditionMonitor(): NetworkConditionMonitor
