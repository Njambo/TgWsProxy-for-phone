package org.tgwsproxy.mobile

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Screen()
                }
            }
        }
    }
}

@Composable
private fun Screen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("proxy", Context.MODE_PRIVATE) }

    val status by ProxyRuntime.status.collectAsState()
    val logs by ProxyRuntime.logs.collectAsState()
    val running = status is ProxyStatus.Running

    var portText by remember {
        mutableStateOf(prefs.getInt("port", ProxyService.DEFAULT_PORT).toString())
    }
    var secretHex by remember {
        mutableStateOf(prefs.getString("secret", null) ?: randomSecretHex())
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun startProxy() {
        val port = portText.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            Toast.makeText(context, "Порт должен быть от 1024 до 65535", Toast.LENGTH_SHORT).show()
            return
        }
        requestNotificationPermissionIfNeeded()
        prefs.edit().putInt("port", port).putString("secret", secretHex).apply()
        ProxyService.start(context, port, secretHex)
    }

    fun stopProxy() = ProxyService.stop(context)

    fun proxyLink(): String {
        val port = (status as? ProxyStatus.Running)?.port ?: (portText.toIntOrNull() ?: 1443)
        return "tg://proxy?server=127.0.0.1&port=$port&secret=$secretHex"
    }

    fun copyLink() {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("tg://proxy", proxyLink()))
        Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
    }

    fun openTelegram() {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(proxyLink())))
        } catch (_: Exception) {
            copyLink()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "TG WS Proxy Mobile",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Локальный MTProto-прокси: трафик Telegram идёт через WebSocket " +
                "на серверы web.telegram.org, а не напрямую на DC.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        StatusCard(status)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = portText,
                onValueChange = { value -> portText = value.filter { it.isDigit() }.take(5) },
                label = { Text("Порт") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.width(140.dp)
            )
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = secretHex,
                onValueChange = { },
                readOnly = true,
                singleLine = true,
                label = { Text("Секрет") },
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                modifier = Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                Button(
                    onClick = { stopProxy() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Остановить")
                }
            } else {
                Button(
                    onClick = { startProxy() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Запустить")
                }
                TextButton(
                    onClick = {
                        secretHex = randomSecretHex()
                        prefs.edit().putString("secret", secretHex).apply()
                    }
                ) {
                    Text("Новый секрет")
                }
            }
        }

        OutlinedButton(
            onClick = { openTelegram() },
            enabled = running,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Подключить Telegram")
        }

        TextButton(
            onClick = { copyLink() },
            enabled = running,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Скопировать tg:// ссылку")
        }

        Text(
            "Журнал",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            if (logs.isEmpty()) {
                Text(
                    "Пока пусто",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    items(logs) { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: ProxyStatus) {
    val running = status is ProxyStatus.Running
    val dot = if (running) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    if (running) "Работает" else "Остановлен",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (running) {
                        "127.0.0.1:${(status as ProxyStatus.Running).port}"
                    } else {
                        "Нажмите «Запустить», чтобы поднять прокси"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
