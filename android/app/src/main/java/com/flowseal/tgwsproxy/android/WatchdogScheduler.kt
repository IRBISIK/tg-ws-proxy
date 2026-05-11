package com.flowseal.tgwsproxy.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Chain [AlarmManager] wake-ups so the monitor survives abrupt process kill
 * (when [android.app.Service.onDestroy] never runs).
 */
object WatchdogScheduler {
    private const val REQUEST_CODE = 4001

    fun schedule(context: Context, delayMs: Long = 30_000L) {
        val appContext = context.applicationContext
        val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(appContext, WatchdogReceiver::class.java).apply {
            action = WatchdogReceiver.ACTION_WATCHDOG
        }
        val pending = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val triggerAt = System.currentTimeMillis() + delayMs.coerceAtLeast(15_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            @Suppress("DEPRECATION")
            alarm.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(appContext, WatchdogReceiver::class.java).apply {
            action = WatchdogReceiver.ACTION_WATCHDOG
        }
        val pending = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarm.cancel(pending)
        pending.cancel()
    }
}
