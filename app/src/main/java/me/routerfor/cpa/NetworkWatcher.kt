package me.routerfor.cpa

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restarts the gateway when the device's network changes.
 *
 * The embedded engine keeps long-lived connections to the upstream, and a phone changes network
 * underneath it constantly: Wi-Fi to cellular, a captive portal, a dead access point that the
 * system silently falls back from. The old sockets do not fail — they go quiet — so requests sit
 * until a timeout, which is the "connection drops and I have to restart the gateway by hand"
 * report.
 *
 * A restart is cheap here (the engine comes up in about a second and the credentials live on
 * disk), so the policy is deliberately blunt: whenever the default network changes to a
 * different one, restart. It only runs while the user wants the gateway running.
 */
object NetworkWatcher {

    private const val TAG = "CpaNetwork"

    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start(context: Context) {
        if (callback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        // The application context, not the service: this callback outlives individual start/stop
        // cycles, and holding a Service reference here would keep it alive.
        val appCtx = context.applicationContext
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                restartForNewNetwork(appCtx, network)
            }
        }
        // registerDefaultNetworkCallback, not registerNetworkCallback. The latter fires for ANY
        // network that satisfies the request, so a phone with Wi-Fi and cellular both up gets an
        // onAvailable for each — plus background IMS and VPN churn — and would restart the
        // gateway while the default route never changed. The default-network callback fires only
        // when the route the app actually uses changes, and it does not fire on registration, so
        // the "first callback is not a change" bookkeeping goes away with it.
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { callback = cb }
            .onFailure { Log.w(TAG, "network callback registration failed: " + it.message) }
    }

    @Synchronized
    fun stop(context: Context) {
        val cb = callback ?: return
        callback = null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
            .onFailure { Log.d(TAG, "unregister: " + it.message) }
    }

    private fun restartForNewNetwork(context: Context, network: Network) {
        if (!AppPrefs.gatewayWanted(context)) {
            Log.i(TAG, "network changed but the gateway is stopped on purpose; not restarting")
            return
        }
        if (!WebBridgeCompat.isRunning()) {
            // Already down: the start path handles it, and a restart here would race that.
            Log.i(TAG, "network changed while the gateway is down; leaving it to the start path")
            return
        }
        Log.i(TAG, "network changed to " + network + "; restarting the gateway")
        // A network callback fires on a system thread with no activity attached, so the
        // foreground-service intent is the only way to reach the engine. It is already running,
        // which is what makes startForegroundService legal here even from the background.
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProxyForegroundService::class.java)
                    .setAction(ProxyForegroundService.ACTION_RESTART)
            )
        }.onFailure { Log.w(TAG, "restart after network change failed: " + it.message) }
    }
}
