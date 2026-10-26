package me.routerfor.cpa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Routes notification / panel broadcast actions into the foreground service. */
class ProxyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val target = Intent(context, ProxyForegroundService::class.java)
        when (action) {
            ProxyForegroundService.ACTION_RESTART, ProxyForegroundService.ACTION_STOP -> {
                target.action = action
                if (ProxyForegroundService.ACTION_STOP == action) {
                    context.startService(target)
                } else {
                    context.startForegroundService(target)
                }
            }
        }
    }
}
