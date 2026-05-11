package com.flowseal.tgwsproxy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File

class ProxyService : Service() {
    private var autoStartOnTelegram = true
    private var startedAtMs: Long = 0L
    private var activeHost: String = "127.0.0.1"
    private var activePort: Int = 1443
    private var lastConfigJson: String? = null
    private var lastLogFile: String? = null
    private var manualStopRequested = false
    private var lastAutoStartAttemptMs = 0L
    private var pendingAutoStartAfterManualStop = false
    private var telegramUseTimeAtManualStop = 0L
    private var desiredProxyRunning = false
    private var lastBridgeRunning = false
    private var lastBridgeError = ""
    private var lastBridgeStatusAtMs = 0L
    private var lastTelegramActive = false
    private var lastTelegramCheckAtMs = 0L
    private val notificationHandler = Handler(Looper.getMainLooper())
    private val notificationUpdater = object : Runnable {
        override fun run() {
            NotificationManagerCompat.from(this@ProxyService).notify(NOTIFICATION_ID, buildNotification())
            notificationHandler.postDelayed(this, nextMonitorDelayMs())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        restoreRuntimeState()
        scheduleWatchdog()

        val action = intent?.action ?: ACTION_ENSURE_MONITOR
        if (action == ACTION_ENSURE_MONITOR) {
            val cfgJson = intent?.getStringExtra(EXTRA_CONFIG_JSON)
                ?: loadSavedConfigJson()
                ?: ProxyConfig.default().toJson()
            val logFile = intent?.getStringExtra(EXTRA_LOG_FILE)
                ?: loadSavedLogFile()
                ?: defaultLogFilePath()
            val cfg = ProxyConfig.fromJson(cfgJson)
            activeHost = cfg.host
            activePort = cfg.port
            autoStartOnTelegram = cfg.autoStartOnTelegram
            lastConfigJson = cfgJson
            lastLogFile = logFile
            saveState(cfgJson, logFile)
            manualStopRequested = false
            notificationHandler.removeCallbacks(notificationUpdater)
            notificationHandler.post(notificationUpdater)
            if (desiredProxyRunning && !readBridgeStatusCached().first) {
                startPythonProxy(cfgJson, logFile)
            }
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
            return START_STICKY
        }

        if (action == ACTION_STOP) {
            // Stop only the proxy process; keep monitor/config alive
            // so next Telegram foreground event can auto-start again.
            manualStopRequested = false
            pendingAutoStartAfterManualStop = true
            telegramUseTimeAtManualStop = latestTelegramUseTime()
            desiredProxyRunning = false
            persistRuntimeState()
            startedAtMs = 0L
            stopPythonProxy()
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
            notificationHandler.removeCallbacks(notificationUpdater)
            notificationHandler.post(notificationUpdater)
            return START_STICKY
        }

        val cfgJson = intent?.getStringExtra(EXTRA_CONFIG_JSON)
            ?: loadSavedConfigJson()
            ?: ProxyConfig.default().toJson()
        val logFile = intent?.getStringExtra(EXTRA_LOG_FILE)
            ?: loadSavedLogFile()
            ?: defaultLogFilePath()
        val cfg = ProxyConfig.fromJson(cfgJson)
        lastConfigJson = cfgJson
        lastLogFile = logFile
        saveState(cfgJson, logFile)
        activeHost = cfg.host
        activePort = cfg.port
        autoStartOnTelegram = cfg.autoStartOnTelegram
        if (startedAtMs == 0L) startedAtMs = System.currentTimeMillis()
        manualStopRequested = false
        pendingAutoStartAfterManualStop = false
        telegramUseTimeAtManualStop = 0L
        desiredProxyRunning = true
        persistRuntimeState()
        startPythonProxy(cfgJson, logFile)
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        notificationHandler.removeCallbacks(notificationUpdater)
        notificationHandler.post(notificationUpdater)
        return START_STICKY
    }

    override fun onDestroy() {
        notificationHandler.removeCallbacks(notificationUpdater)
        scheduleWatchdog()
        if (manualStopRequested) {
            stopPythonProxy()
        } else {
            // Keep proxy alive even when task/app UI is closed.
            restartSelf()
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User removed app from recents: keep foreground proxy service alive.
        scheduleWatchdog()
        restartSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPythonProxy(cfgJson: String, logFile: String) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        val py = Python.getInstance()
        py.getModule("android_proxy_bridge").callAttr("start_proxy", cfgJson, logFile)
    }

    private fun stopPythonProxy() {
        if (!Python.isStarted()) return
        val py = Python.getInstance()
        py.getModule("android_proxy_bridge").callAttr("stop_proxy")
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            2001,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(this, ProxyService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2002,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val uptimeMinutes = ((System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L) / 60000L)
        val bridge = readBridgeStatusCached()
        maybeAutoStartByTelegram(bridge.first)
        val state = if (bridge.first) "ON" else "OFF"
        val mode = if (autoStartOnTelegram) "auto" else "manual"
        val statusText = "[$state/$mode] $activeHost:$activePort | uptime ${uptimeMinutes}m"
        val details = if (bridge.second.isNotBlank()) {
            "$statusText\nОшибка: ${bridge.second.take(120)}"
        } else {
            "$statusText\nПрокси активен в фоне. Нажмите, чтобы открыть управление."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TG WS Proxy")
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setSmallIcon(android.R.drawable.presence_online)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPendingIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TG WS Proxy",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val PREFS_NAME = "proxy_service_state"
        const val KEY_MONITOR_ENABLED = "monitor_enabled"
        private const val KEY_CONFIG_JSON = "config_json"
        private const val KEY_LOG_FILE = "log_file"
        private const val KEY_PENDING_AFTER_STOP = "pending_after_stop"
        private const val KEY_TELEGRAM_USE_AT_STOP = "telegram_use_at_stop"
        private const val KEY_DESIRED_PROXY_RUNNING = "desired_proxy_running"
        private const val CHANNEL_ID = "tg_ws_proxy_service"
        private const val NOTIFICATION_ID = 31
        private const val ACTION_START = "com.flowseal.tgwsproxy.action.START"
        private const val ACTION_STOP = "com.flowseal.tgwsproxy.action.STOP"
        private const val ACTION_ENSURE_MONITOR = "com.flowseal.tgwsproxy.action.ENSURE_MONITOR"
        private const val EXTRA_CONFIG_JSON = "config_json"
        private const val EXTRA_LOG_FILE = "log_file"

        fun start(context: Context, config: ProxyConfig, logFile: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MONITOR_ENABLED, true)
                .apply()
            val payload = config.toJson()
            val intent = Intent(context, ProxyService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG_JSON, payload)
                putExtra(EXTRA_LOG_FILE, logFile)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProxyService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        fun shouldAutoHealMonitor(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_MONITOR_ENABLED, false)) return true
            if (prefs.getBoolean(KEY_DESIRED_PROXY_RUNNING, false)) return true
            if (prefs.getBoolean(KEY_PENDING_AFTER_STOP, false)) return true
            if (prefs.getString(KEY_CONFIG_JSON, null) != null) return true
            return File(context.filesDir, MainActivity.CONFIG_FILE_NAME).exists()
        }

        fun ensureMonitor(context: Context, config: ProxyConfig? = null, logFile: String? = null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MONITOR_ENABLED, true)
                .apply()
            val intent = Intent(context, ProxyService::class.java).apply {
                action = ACTION_ENSURE_MONITOR
            }
            if (config != null && !logFile.isNullOrBlank()) {
                intent.putExtra(EXTRA_CONFIG_JSON, config.toJson())
                intent.putExtra(EXTRA_LOG_FILE, logFile)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private fun readBridgeStatus(): Pair<Boolean, String> {
        return try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(this))
            val py = Python.getInstance()
            val bridge = py.getModule("android_proxy_bridge")
            val status = JSONObject(bridge.callAttr("get_status_json").toString())
            status.optBoolean("running", false) to status.optString("last_error", "")
        } catch (e: Exception) {
            false to (e.message ?: "bridge error")
        }
    }

    private fun readBridgeStatusCached(force: Boolean = false): Pair<Boolean, String> {
        val now = System.currentTimeMillis()
        val ttl = if (lastBridgeRunning) 8_000L else 15_000L
        if (!force && now - lastBridgeStatusAtMs < ttl) {
            return lastBridgeRunning to lastBridgeError
        }
        val status = readBridgeStatus()
        lastBridgeRunning = status.first
        lastBridgeError = status.second
        lastBridgeStatusAtMs = now
        return status
    }

    private fun restartSelf() {
        val cfg = lastConfigJson ?: loadSavedConfigJson() ?: ProxyConfig.default().toJson()
        val log = lastLogFile ?: loadSavedLogFile() ?: defaultLogFilePath()
        val restartIntent = Intent(this, ProxyService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_CONFIG_JSON, cfg)
            putExtra(EXTRA_LOG_FILE, log)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }
    }

    private fun saveState(cfgJson: String, logFile: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIG_JSON, cfgJson)
            .putString(KEY_LOG_FILE, logFile)
            .apply()
    }

    private fun clearPersistedState() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(KEY_CONFIG_JSON)
            .remove(KEY_LOG_FILE)
            .apply()
    }

    private fun loadSavedConfigJson(): String? =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_CONFIG_JSON, null)

    private fun loadSavedLogFile(): String? =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_LOG_FILE, null)

    private fun maybeAutoStartByTelegram(isProxyRunning: Boolean) {
        if (!autoStartOnTelegram || isProxyRunning) return
        if (!hasUsageStatsPermission()) return
        val telegramActive = isTelegramActiveCached()
        if (pendingAutoStartAfterManualStop) {
            val currentUseTime = latestTelegramUseTime()
            if (currentUseTime <= telegramUseTimeAtManualStop + 1000L) {
                return
            }
        } else if (!telegramActive) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastAutoStartAttemptMs < 4000L) return
        lastAutoStartAttemptMs = now
        val cfg = lastConfigJson ?: loadSavedConfigJson() ?: ProxyConfig.default().toJson()
        val log = lastLogFile ?: loadSavedLogFile() ?: defaultLogFilePath()
        startPythonProxy(cfg, log)
        pendingAutoStartAfterManualStop = false
        telegramUseTimeAtManualStop = 0L
        desiredProxyRunning = true
        persistRuntimeState()
        if (startedAtMs == 0L) startedAtMs = System.currentTimeMillis()
    }

    private fun hasUsageStatsPermission(): Boolean {
        val manager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = manager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isTelegramActive(): Boolean {
        if (isTelegramForegroundByEvents()) return true
        return isTelegramRecentlyUsedByStats()
    }

    private fun isTelegramActiveCached(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val ttl = 5_000L
        if (!force && now - lastTelegramCheckAtMs < ttl) {
            return lastTelegramActive
        }
        lastTelegramActive = isTelegramActive()
        lastTelegramCheckAtMs = now
        return lastTelegramActive
    }

    private fun isTelegramForegroundByEvents(): Boolean {
        val usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - 15_000L
        val events = usage.queryEvents(begin, end)
        val event = UsageEvents.Event()
        var lastForegroundPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastForegroundPkg = event.packageName
            }
        }
        return lastForegroundPkg == "org.telegram.messenger" ||
            lastForegroundPkg == "org.telegram.plus" ||
            lastForegroundPkg == "org.thunderdog.challegram"
    }

    private fun isTelegramRecentlyUsedByStats(): Boolean {
        val usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - 120_000L
        val stats = usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
        if (stats.isNullOrEmpty()) return false
        val lastTelegramUse = stats
            .filter { s ->
                s.packageName == "org.telegram.messenger" ||
                    s.packageName == "org.telegram.plus" ||
                    s.packageName == "org.thunderdog.challegram"
            }
            .maxOfOrNull { it.lastTimeUsed } ?: return false
        return end - lastTelegramUse <= 20_000L
    }

    private fun latestTelegramUseTime(): Long {
        val usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val begin = end - 86_400_000L
        val stats = usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
        if (stats.isNullOrEmpty()) return 0L
        return stats
            .filter { s ->
                s.packageName == "org.telegram.messenger" ||
                    s.packageName == "org.telegram.plus" ||
                    s.packageName == "org.thunderdog.challegram"
            }
            .maxOfOrNull { it.lastTimeUsed } ?: 0L
    }

    private fun defaultLogFilePath(): String =
        "${filesDir.absolutePath}/tg_ws_proxy.log"

    private fun scheduleWatchdog() {
        WatchdogScheduler.schedule(this)
    }

    private fun nextMonitorDelayMs(): Long {
        return when {
            lastBridgeRunning -> 10_000L
            autoStartOnTelegram -> 6_000L
            else -> 20_000L
        }
    }

    private fun persistRuntimeState() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PENDING_AFTER_STOP, pendingAutoStartAfterManualStop)
            .putLong(KEY_TELEGRAM_USE_AT_STOP, telegramUseTimeAtManualStop)
            .putBoolean(KEY_DESIRED_PROXY_RUNNING, desiredProxyRunning)
            .apply()
    }

    private fun restoreRuntimeState() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        pendingAutoStartAfterManualStop = prefs.getBoolean(KEY_PENDING_AFTER_STOP, false)
        telegramUseTimeAtManualStop = prefs.getLong(KEY_TELEGRAM_USE_AT_STOP, 0L)
        desiredProxyRunning = prefs.getBoolean(KEY_DESIRED_PROXY_RUNNING, false)
    }
}
