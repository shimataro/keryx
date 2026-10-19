package works.merc.keryx.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.NetworkCondition

actual fun platformNetworkConditionMonitor(): NetworkConditionMonitor =
    AndroidNetworkConditionMonitor(AndroidAppContext.application)

/**
 * Tracks the default network's metered state and the user's Data Saver setting through
 * [ConnectivityManager].
 *
 * The initial value is read synchronously at construction, so a caller woken by `WorkManager` a
 * moment ago never acts on a placeholder. After that, the default-network callback follows network
 * changes, and [ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED] follows Data Saver being
 * switched (or this app being exempted from it).
 *
 * No network at all counts as unrestricted: nothing is deferred, and a request simply fails as it
 * would have anyway.
 */
internal class AndroidNetworkConditionMonitor(context: Context) : NetworkConditionMonitor {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(readCurrent())

    override val condition: Flow<NetworkCondition> = state.asStateFlow()
    override val supported: Boolean = true

    init {
        runCatching {
            connectivity.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        state.value = conditionOf(capabilities)
                    }

                    override fun onLost(network: Network) {
                        state.value = readCurrent()
                    }
                },
            )
        }.onFailure { Log.warn(TAG, "Could not watch the default network: ${it.message}") }
        // Data Saver is delivered only to receivers registered at runtime. A system broadcast, so
        // the receiver need not be exported.
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    state.value = readCurrent()
                }
            },
            IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun readCurrent(): NetworkCondition {
        val capabilities = runCatching {
            connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        }.getOrNull() ?: return NetworkCondition.UNRESTRICTED
        return conditionOf(capabilities)
    }

    private fun conditionOf(capabilities: NetworkCapabilities): NetworkCondition {
        val unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
                )
        val metered = !unmetered
        // Data Saver only ever applies to metered networks, so it means nothing on Wi-Fi.
        val dataSaver = metered &&
            connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        return NetworkCondition(metered = metered, dataSaver = dataSaver)
    }

    private companion object {
        const val TAG = "NetworkCondition"
    }
}
