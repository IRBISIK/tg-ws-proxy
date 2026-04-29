package com.flowseal.tgwsproxy.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private val configFileName = "tg_ws_proxy_config.json"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProxyScreen(
                        initial = loadConfig(),
                        onSave = { saveConfig(it) },
                        onStartProxy = { cfg ->
                            saveConfig(cfg)
                            ProxyService.start(this, cfg, logFile().absolutePath)
                        },
                        onStopProxy = { ProxyService.stop(this) },
                        proxyStatus = { readBridgeStatus() },
                        logText = { readLogTail() },
                        onOpenTelegram = { url ->
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                    )
                }
            }
        }
    }

    private fun loadConfig(): ProxyConfig {
        return try {
            val file = File(filesDir, configFileName)
            if (!file.exists()) return ProxyConfig.default()
            val root = JSONObject(file.readText())
            ProxyConfig(
                host = root.optString("host", "127.0.0.1"),
                port = root.optInt("port", 1443),
                secret = root.optString("secret", randomSecret()),
                dcIp = root.optJSONArray("dc_ip")?.let {
                    buildList {
                        for (i in 0 until it.length()) add(it.getString(i))
                    }
                } ?: listOf("2:149.154.167.220", "4:149.154.167.220"),
                cfProxy = root.optBoolean("cfproxy", true),
                cfProxyPriority = root.optBoolean("cfproxy_priority", true),
                cfProxyUserDomain = root.optString("cfproxy_user_domain", ""),
                appearance = root.optString("appearance", "auto"),
            )
        } catch (_: Exception) {
            ProxyConfig.default()
        }
    }

    private fun saveConfig(config: ProxyConfig) {
        val root = JSONObject().apply {
            put("host", config.host)
            put("port", config.port)
            put("secret", config.secret)
            put("dc_ip", JSONArray(config.dcIp))
            put("cfproxy", config.cfProxy)
            put("cfproxy_priority", config.cfProxyPriority)
            put("cfproxy_user_domain", config.cfProxyUserDomain)
            put("appearance", config.appearance)
        }
        File(filesDir, configFileName).writeText(root.toString(2))
    }

    private fun logFile(): File = File(filesDir, "tg_ws_proxy.log")

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

    private fun readLogTail(): String {
        return try {
            val file = logFile()
            if (!file.exists()) return "Логи появятся после запуска прокси."
            val lines = file.readLines()
            lines.takeLast(60).joinToString("\n")
        } catch (e: Exception) {
            "Не удалось прочитать логи: ${e.message}"
        }
    }
}

data class ProxyConfig(
    val host: String,
    val port: Int,
    val secret: String,
    val dcIp: List<String>,
    val cfProxy: Boolean,
    val cfProxyPriority: Boolean,
    val cfProxyUserDomain: String,
    val appearance: String,
) {
    fun tgUrl(): String = "tg://proxy?server=$host&port=$port&secret=dd$secret"
    fun toJson(): String = JSONObject().apply {
        put("host", host)
        put("port", port)
        put("secret", secret)
        put("dc_ip", JSONArray(dcIp))
        put("cfproxy", cfProxy)
        put("cfproxy_priority", cfProxyPriority)
        put("cfproxy_user_domain", cfProxyUserDomain)
        put("appearance", appearance)
        put("buf_kb", 256)
        put("pool_size", 4)
        put("verbose", false)
    }.toString()

    companion object {
        fun fromJson(value: String): ProxyConfig {
            val root = JSONObject(value)
            return ProxyConfig(
                host = root.optString("host", "127.0.0.1"),
                port = root.optInt("port", 1443),
                secret = root.optString("secret", randomSecret()),
                dcIp = root.optJSONArray("dc_ip")?.let {
                    buildList {
                        for (i in 0 until it.length()) add(it.getString(i))
                    }
                } ?: listOf("2:149.154.167.220", "4:149.154.167.220"),
                cfProxy = root.optBoolean("cfproxy", true),
                cfProxyPriority = root.optBoolean("cfproxy_priority", true),
                cfProxyUserDomain = root.optString("cfproxy_user_domain", ""),
                appearance = root.optString("appearance", "auto"),
            )
        }

        fun default() = ProxyConfig(
            host = "127.0.0.1",
            port = 1443,
            secret = randomSecret(),
            dcIp = listOf("2:149.154.167.220", "4:149.154.167.220"),
            cfProxy = true,
            cfProxyPriority = true,
            cfProxyUserDomain = "",
            appearance = "auto",
        )
    }
}

private fun randomSecret(): String {
    val chars = "0123456789abcdef"
    return buildString {
        repeat(32) { append(chars[Random.nextInt(chars.length)]) }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
private fun ProxyScreen(
    initial: ProxyConfig,
    onSave: (ProxyConfig) -> Unit,
    onStartProxy: (ProxyConfig) -> Unit,
    onStopProxy: () -> Unit,
    proxyStatus: () -> Pair<Boolean, String>,
    logText: () -> String,
    onOpenTelegram: (String) -> Unit,
) {
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var secret by remember { mutableStateOf(initial.secret) }
    var dcIpText by remember { mutableStateOf(initial.dcIp.joinToString("\n")) }
    var info by remember { mutableStateOf("Готово к запуску") }
    var isRunning by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf("Логи появятся после запуска прокси.") }

    LaunchedEffect(Unit) {
        while (true) {
            val status = proxyStatus()
            isRunning = status.first
            lastError = status.second
            logs = logText()
            delay(1500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("TG WS Proxy", fontWeight = FontWeight.Bold)
                        Text(
                            if (isRunning) "Прокси активен" else "Прокси остановлен",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning) Color(0xFF173E2A) else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (isRunning) "Статус: работает" else "Статус: не запущен",
                        fontWeight = FontWeight.SemiBold,
                        color = if (isRunning) Color(0xFF8AF2BF) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Локально: ${cfgHostPort(host, port)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRunning) Color(0xFFD9FCEB) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Управление и мониторинг в реальном времени",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isRunning) Color(0xFFB8F5D8) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (lastError.isNotBlank()) {
                Text("Ошибка: $lastError", color = MaterialTheme.colorScheme.error)
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it.lowercase().filter { ch -> ch in "0123456789abcdef" }.take(32) },
                        label = { Text("Secret (32 hex)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = dcIpText,
                        onValueChange = { dcIpText = it },
                        label = { Text("DC -> IP (one per line)") },
                        modifier = Modifier.fillMaxWidth().height(130.dp),
                    )
                }
            }

            val cfg = ProxyConfig(
                host = host.ifBlank { "127.0.0.1" },
                port = port.toIntOrNull() ?: 1443,
                secret = if (secret.length == 32) secret else randomSecret(),
                dcIp = dcIpText.lines().map { it.trim() }.filter { it.isNotBlank() },
                cfProxy = true,
                cfProxyPriority = true,
                cfProxyUserDomain = "",
                appearance = "auto",
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onSave(cfg)
                    info = "Конфиг сохранен"
                }) { Text("Сохранить") }
                FilledTonalButton(onClick = {
                    onStartProxy(cfg)
                    info = "Запуск прокси..."
                }) { Text("Старт") }
                FilledTonalButton(onClick = {
                    onStopProxy()
                    info = "Остановка прокси..."
                }) { Text("Стоп") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onOpenTelegram(cfg.tgUrl()) }) { Text("Открыть в Telegram") }
                TextButton(onClick = { info = "Ссылка: ${cfg.tgUrl()}" }) { Text("Показать ссылку") }
            }

            Text(info, style = MaterialTheme.typography.bodyMedium)
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Логи", fontWeight = FontWeight.SemiBold)
                    Text(
                        logs,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 18,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun cfgHostPort(host: String, port: String): String {
    val safeHost = host.ifBlank { "127.0.0.1" }
    val safePort = port.ifBlank { "1443" }
    return "$safeHost:$safePort"
}
