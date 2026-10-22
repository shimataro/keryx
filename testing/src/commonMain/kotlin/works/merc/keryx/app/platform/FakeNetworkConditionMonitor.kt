package works.merc.keryx.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import works.merc.keryx.app.core.NetworkCondition

/** A [NetworkConditionMonitor] whose condition a test sets directly through [current]. */
class FakeNetworkConditionMonitor(
    initial: NetworkCondition = NetworkCondition.UNRESTRICTED,
    override val supported: Boolean = true,
) : NetworkConditionMonitor {
    val current = MutableStateFlow(initial)
    override val condition = current
}
