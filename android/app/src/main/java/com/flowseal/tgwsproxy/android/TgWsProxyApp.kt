package com.flowseal.tgwsproxy.android

import android.app.Application

class TgWsProxyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // После жёсткого kill процесс может не вызвать Service.onDestroy — будильник не ставился.
        if (ProxyService.shouldAutoHealMonitor(this)) {
            WatchdogScheduler.schedule(this)
            ProxyService.ensureMonitor(this)
        }
    }
}
