package com.flowseal.tgwsproxy.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_WATCHDOG) return
        val app = context.applicationContext
        WatchdogScheduler.schedule(app)
        if (ProxyService.shouldAutoHealMonitor(app)) {
            ProxyService.ensureMonitor(app)
        }
    }

    companion object {
        const val ACTION_WATCHDOG = "com.flowseal.tgwsproxy.action.WATCHDOG"
    }
}
