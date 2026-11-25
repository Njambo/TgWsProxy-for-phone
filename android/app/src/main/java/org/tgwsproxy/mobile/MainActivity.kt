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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import org.tgwsproxy.mobile.core.RouteProbe
import org.tgwsproxy.mobile.core.parseDomains

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("proxy", Context.MODE_PRIVATE)
        setContent {
            var themeMode by remember {
                mutableStateOf(ThemeMode.fromName(prefs.getString("theme", null)))
            }
            val dark = when (themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            val view = LocalView.current
            SideEffect {
                // С Android 15 приложение рисуется под системными панелями:
                // подбираем цвет их значков под выбранную тему.
                if (Build.VERSION.SDK_INT >= 35) {
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !dark
                    controller.isAppearanceLightNavigationBars = !dark
                }
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(top = 8.dp)
                    ) {
                        Screen(
                            themeMode = themeMode,
                            onThemeChange = { mode ->
                                themeMode = mode
                                prefs.edit().putString("theme", mode.name).apply()
                            }
                        )
                    }
                }
            }
        }
    }
}

private enum class ThemeMode {
    System, Light, Dark;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: ThemeMode.System
    }
}

private enum class AppScreen { Main, Log, Settings }

@Composable
private fun Screen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("proxy", Context.MODE_PRIVATE) }

    val status by ProxyRuntime.status.collectAsState()
    val route by ProxyRuntime.route.collectAsState()
    val logs by ProxyRuntime.logs.collectAsState()
    val running = status is ProxyStatus.Running

    var screen by remember { mutableStateOf(AppScreen.Main) }

    var portText by remember {
        mutableStateOf(prefs.getInt("port", ProxyService.DEFAULT_PORT).toString())
    }
    var secretHex by remember {
        mutableStateOf(prefs.getString("secret", null) ?: randomSecretHex())
    }
    var workerDomain by remember {
        mutableStateOf(prefs.getString("worker", null) ?: "")
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
        prefs.edit()
            .putInt("port", port)
            .putString("secret", secretHex)
            .putString("worker", workerDomain)
            .apply()
        ProxyService.start(context, port, secretHex, workerDomain)
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

    fun copyLogs() {
        val text = ProxyRuntime.snapshot()
        if (text.isEmpty()) {
            Toast.makeText(context, "Журнал пуст", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Журнал", text))
        Toast.makeText(context, "Журнал скопирован", Toast.LENGTH_SHORT).show()
    }

    fun openTelegram() {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(proxyLink())))
        } catch (_: Exception) {
            copyLink()
        }
    }

    fun runProbe() {
        ProxyRuntime.log("— проверка соединения —")
        prefs.edit().putString("worker", workerDomain).apply()
        RouteProbe.launch(
            hexToBytes(secretHex),
            parseDomains(workerDomain),
            log = { line -> ProxyRuntime.log(line) },
        )
        screen = AppScreen.Log
    }

    val scope = rememberCoroutineScope()
    val versionName = remember { Updater.currentVersionName(context) }
    var updateInfo by remember { mutableStateOf<UpdateCheck.Available?>(null) }
    var downloadProgress by remember { mutableStateOf<Int?>(null) }
    var checkingUpdates by remember { mutableStateOf(false) }

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
        }
    }

    fun checkUpdates(manual: Boolean) {
        if (checkingUpdates) return
        checkingUpdates = true
        scope.launch {
            when (val result = Updater.check(context)) {
                is UpdateCheck.Available -> updateInfo = result
                UpdateCheck.UpToDate -> if (manual) {
                    Toast.makeText(context, "У вас последняя версия", Toast.LENGTH_SHORT).show()
                }
                is UpdateCheck.Failed -> if (manual) {
                    Toast.makeText(
                        context,
                        "Не удалось проверить обновления: ${result.reason}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            checkingUpdates = false
        }
    }

    fun startUpdate(info: UpdateCheck.Available) {
        val apkUrl = info.apkUrl
        if (apkUrl == null) {
            openUrl(info.pageUrl)
            return
        }
        if (!Updater.canInstall(context)) {
            Toast.makeText(
                context,
                "Разрешите установку из этого приложения и нажмите «Обновить» снова",
                Toast.LENGTH_LONG
            ).show()
            Updater.openInstallSettings(context)
            return
        }
        scope.launch {
            downloadProgress = 0
            try {
                val file = Updater.download(context, apkUrl) { downloadProgress = it }
                downloadProgress = null
                updateInfo = null
                Updater.install(context, file)
            } catch (e: Exception) {
                downloadProgress = null
                Toast.makeText(
                    context,
                    "Не удалось скачать обновление. Попробуйте ссылку.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LaunchedEffect(Unit) { checkUpdates(manual = false) }

    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { if (downloadProgress == null) updateInfo = null },
            title = { Text("Доступно обновление") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(info.title, fontWeight = FontWeight.SemiBold)
                    if (info.notes.isNotBlank()) {
                        Text(info.notes.trim().take(400), style = MaterialTheme.typography.bodySmall)
                    }
                    downloadProgress?.let { percent ->
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Скачивание: $percent%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { startUpdate(info) },
                    enabled = downloadProgress == null
                ) { Text("Обновить") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { openUrl(info.pageUrl) }) { Text("Ссылка") }
                    TextButton(
                        onClick = { updateInfo = null },
                        enabled = downloadProgress == null
                    ) { Text("Позже") }
                }
            }
        )
    }

    BackHandler(enabled = screen != AppScreen.Main) { screen = AppScreen.Main }

    if (screen == AppScreen.Settings) {
        SettingsScreen(
            themeMode = themeMode,
            onThemeChange = onThemeChange,
            versionName = versionName,
            checkingUpdates = checkingUpdates,
            onCheckUpdates = { checkUpdates(manual = true) },
            onBack = { screen = AppScreen.Main },
        )
        return
    }

    if (screen == AppScreen.Log) {
        LogScreen(
            logCount = logs.size,
            onBack = { screen = AppScreen.Main },
            onProbe = { runProbe() },
            onCopy = { copyLogs() },
            onClear = { ProxyRuntime.clearLogs() },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "TG WS Proxy Mobile",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { screen = AppScreen.Settings }) {
                Text("⚙", fontSize = 24.sp)
            }
        }
        Text(
            "Локальный MTProto-прокси: трафик Telegram идёт через WebSocket " +
                "на серверы web.telegram.org, а не напрямую на DC.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        StatusCard(status, route)

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

        OutlinedTextField(
            value = workerDomain,
            onValueChange = { value ->
                workerDomain = value.trim()
                prefs.edit().putString("worker", workerDomain).apply()
            },
            label = { Text("Свой Cloudflare Worker") },
            placeholder = { Text("myproxy.workers.dev") },
            supportingText = {
                Text("Необязательно. Свой адрес обходит блокировку общих доменов.")
            },
            singleLine = true,
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        )

        TextButton(
            onClick = {
                openUrl("https://github.com/Njambo/TgWsProxy-for-phone#свой-cloudflare-worker")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Как создать свой Worker (бесплатно)")
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { screen = AppScreen.Log },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (logs.isEmpty()) "Журнал" else "Журнал · ${logs.size}")
            }
            OutlinedButton(
                onClick = { runProbe() },
                modifier = Modifier.weight(1f)
            ) {
                Text("Проверить связь")
            }
        }

        if (logs.isNotEmpty()) {
            Text(
                logs.last(),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(8.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    versionName: String,
    checkingUpdates: Boolean,
    onCheckUpdates: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text(
                "Настройки",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ThemeSelector(themeMode, onThemeChange)

            OutlinedButton(
                onClick = onCheckUpdates,
                enabled = !checkingUpdates,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (checkingUpdates) "Проверка…" else "Проверить обновления")
            }

            Text(
                "Версия $versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThemeSelector(
    current: ThemeMode,
    onChange: (ThemeMode) -> Unit,
) {
    val options = listOf(
        ThemeMode.System to "Авто",
        ThemeMode.Light to "Светлая",
        ThemeMode.Dark to "Тёмная",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Тема оформления",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (mode, label) ->
                val padding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                if (mode == current) {
                    Button(
                        onClick = { onChange(mode) },
                        contentPadding = padding,
                        modifier = Modifier.weight(1f)
                    ) { Text(label, maxLines = 1) }
                } else {
                    OutlinedButton(
                        onClick = { onChange(mode) },
                        contentPadding = padding,
                        modifier = Modifier.weight(1f)
                    ) { Text(label, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun LogScreen(
    logCount: Int,
    onBack: () -> Unit,
    onProbe: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    val logs by ProxyRuntime.logs.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text(
                "Журнал подключений",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                logCount.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = onProbe) { Text("Проверить связь") }
            TextButton(onClick = onCopy) { Text("Копировать") }
            TextButton(onClick = onClear) { Text("Очистить") }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            if (logs.isEmpty()) {
                Text(
                    "Пока пусто. Запустите прокси и откройте Telegram — " +
                        "здесь появятся все шаги подключения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    ) {
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
}

@Composable
private fun StatusCard(status: ProxyStatus, route: String?) {
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
                if (running) {
                    Text(
                        if (route == null) "канал: ещё не подключён"
                        else "канал: $route",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
        }
    }
}
