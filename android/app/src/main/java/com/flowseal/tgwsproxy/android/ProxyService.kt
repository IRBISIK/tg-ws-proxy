package com.flowseal.tgwsproxy.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

class ProxyService : Service() {
    private var startedAtMs: Long = 0L
    private var activeHost: String = "127.0.0.1"
    private var activePort: Int = 1443
    private var lastConfigJson: String? = null
    private var lastLogFile: String? = null
    private var manualStopRequested = false
    private val notificationHandler = Handler(Looper.getMainLooper())
    private val notificationUpdater = object : Runnable {
        override fun run() {
            NotificationManagerCompat.from(this@ProxyService).notify(NOTIFICATION_ID, buildNotification())
            notificationHandler.postDelayed(this, 5000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            manualStopRequested = true
            clearPersistedState()
            stopPythonProxy()
            notificationHandler.removeCallbacks(notificationUpdater)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val cfgJson = intent?.getStringExtra(EXTRA_CONFIG_JSON) ?: loadSavedConfigJson() ?: return START_STICKY
        val logFile = intent?.getStringExtra(EXTRA_LOG_FILE) ?: loadSavedLogFile() ?: return START_STICKY
        val cfg = ProxyConfig.fromJson(cfgJson)
        lastConfigJson = cfgJson
        lastLogFile = logFile
        saveState(cfgJson, logFile)
        activeHost = cfg.host
        activePort = cfg.port
        if (startedAtMs == 0L) startedAtMs = System.currentTimeMillis()
        manualStopRequested = false
        startPythonProxy(cfgJson, logFile)
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        notificationHandler.removeCallbacks(notificationUpdater)
        notificationHandler.post(notificationUpdater)
        return START_STICKY
    }

    override fun onDestroy() {
        notificationHandler.removeCallbacks(notificationUpdater)
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
        val bridge = readBridgeStatus()
        val state = if (bridge.first) "ON" else "OFF"
        val statusText = "[$state] $activeHost:$activePort | uptime ${uptimeMinutes}m"
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
        private const val PREFS_NAME = "proxy_service_state"
        private const val KEY_CONFIG_JSON = "config_json"
        private const val KEY_LOG_FILE = "log_file"
        private const val CHANNEL_ID = "tg_ws_proxy_service"
        private const val NOTIFICATION_ID = 31
        private const val ACTION_START = "com.flowseal.tgwsproxy.action.START"
        private const val ACTION_STOP = "com.flowseal.tgwsproxy.action.STOP"
        private const val EXTRA_CONFIG_JSON = "config_json"
        private const val EXTRA_LOG_FILE = "log_file"

        fun start(context: Context, config: ProxyConfig, logFile: String) {
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

    private fun restartSelf() {
        val cfg = lastConfigJson ?: loadSavedConfigJson() ?: return
        val log = lastLogFile ?: loadSavedLogFile() ?: return
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
}
