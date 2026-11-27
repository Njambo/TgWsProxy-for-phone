package org.tgwsproxy.mobile

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import org.tgwsproxy.mobile.core.CLOUDFLARE_ANYCAST
import org.tgwsproxy.mobile.core.CustomIp
import org.tgwsproxy.mobile.core.IpKind
import org.tgwsproxy.mobile.core.TelegramDc
import org.tgwsproxy.mobile.core.encodeCustomIps
import org.tgwsproxy.mobile.core.ipError
import org.tgwsproxy.mobile.core.parseCustomIps
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import org.tgwsproxy.mobile.core.RouteProbe
import org.tgwsproxy.mobile.core.parseDomains
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("proxy", Context.MODE_PRIVATE)
        setContent {
            var themeMode by remember {
                mutableStateOf(ThemeMode.fromName(prefs.getString("theme", null)))
            }
            var readerMode by remember {
                mutableStateOf(ReaderMode.fromName(prefs.getString("screen_reader", null)))
            }
            val systemReader = rememberSystemScreenReader()
            val readerActive = when (readerMode) {
                ReaderMode.Auto -> systemReader
                ReaderMode.On -> true
                ReaderMode.Off -> false
            }
            var narratorSettings by remember { mutableStateOf(NarratorSettings.load(prefs)) }
            var palette by remember {
                mutableStateOf(Palette.fromName(prefs.getString("palette", null)))
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
            MaterialTheme(colorScheme = palette.colors(dark)) {
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
                        CompositionLocalProvider(LocalScreenReaderMode provides readerActive) {
                            Screen(
                                themeMode = themeMode,
                                onThemeChange = { mode ->
                                    themeMode = mode
                                    prefs.edit().putString("theme", mode.name).apply()
                                },
                                palette = palette,
                                onPaletteChange = { item ->
                                    palette = item
                                    prefs.edit().putString("palette", item.name).apply()
                                },
                                readerMode = readerMode,
                                onReaderModeChange = { mode ->
                                    readerMode = mode
                                    prefs.edit().putString("screen_reader", mode.name).apply()
                                },
                                systemReader = systemReader,
                                narratorSettings = narratorSettings,
                                onNarratorSettingsChange = { value ->
                                    narratorSettings = value
                                    value.save(prefs)
                                },
                            )
                        }
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

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.System -> "Авто"
    ThemeMode.Light -> "Светлая"
    ThemeMode.Dark -> "Тёмная"
}

/**
 * Цветовые схемы. Чтобы добавить новую тему, достаточно добавить сюда ещё один
 * пункт и описать его цвета в colors(): экран «Темы» подхватит его сам.
 */
private enum class Palette(val label: String, val swatch: Color) {
    Standard("Стандартная", Color(0xFF6650A4)),
    Blue("Синяя", Color(0xFF1565C0)),
    Green("Зелёная", Color(0xFF2E7D32)),
    Orange("Оранжевая", Color(0xFFE65100)),
    Pink("Розовая", Color(0xFFC2185B)),
    Amoled("AMOLED (чёрная)", Color(0xFF000000));

    fun colors(dark: Boolean): ColorScheme = when (this) {
        Standard -> if (dark) darkColorScheme() else lightColorScheme()
        Blue -> if (dark) {
            darkColorScheme(
                primary = Color(0xFF9ECAFF), onPrimary = Color(0xFF003258),
                primaryContainer = Color(0xFF00497D), onPrimaryContainer = Color(0xFFD1E4FF)
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF1565C0), onPrimary = Color.White,
                primaryContainer = Color(0xFFD3E3FD), onPrimaryContainer = Color(0xFF001B3E)
            )
        }
        Green -> if (dark) {
            darkColorScheme(
                primary = Color(0xFF8BD88F), onPrimary = Color(0xFF003915),
                primaryContainer = Color(0xFF1B5E20), onPrimaryContainer = Color(0xFFC8E6C9)
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF2E7D32), onPrimary = Color.White,
                primaryContainer = Color(0xFFC8E6C9), onPrimaryContainer = Color(0xFF00210B)
            )
        }
        Orange -> if (dark) {
            darkColorScheme(
                primary = Color(0xFFFFB68F), onPrimary = Color(0xFF5B1A00),
                primaryContainer = Color(0xFF813000), onPrimaryContainer = Color(0xFFFFDBC8)
            )
        } else {
            lightColorScheme(
                primary = Color(0xFFE65100), onPrimary = Color.White,
                primaryContainer = Color(0xFFFFDBC8), onPrimaryContainer = Color(0xFF380D00)
            )
        }
        Pink -> if (dark) {
            darkColorScheme(
                primary = Color(0xFFFFB1C8), onPrimary = Color(0xFF650033),
                primaryContainer = Color(0xFF8E0E4B), onPrimaryContainer = Color(0xFFFFD9E2)
            )
        } else {
            lightColorScheme(
                primary = Color(0xFFC2185B), onPrimary = Color.White,
                primaryContainer = Color(0xFFFFD9E2), onPrimaryContainer = Color(0xFF3E001D)
            )
        }
        // Чёрный фон для OLED-экранов; в светлом режиме остаётся стандартная схема.
        Amoled -> if (dark) {
            darkColorScheme(
                background = Color.Black,
                surface = Color.Black,
                surfaceVariant = Color(0xFF1C1C1E)
            )
        } else {
            lightColorScheme()
        }
    }

    companion object {
        fun fromName(name: String?): Palette =
            entries.firstOrNull { it.name == name } ?: Standard
    }
}

/** Режим для экранного диктора: Авто включается, когда в системе включён TalkBack. */
private enum class ReaderMode {
    Auto, On, Off;

    companion object {
        fun fromName(name: String?): ReaderMode =
            entries.firstOrNull { it.name == name } ?: Auto
    }
}

private fun ReaderMode.label(): String = when (this) {
    ReaderMode.Auto -> "Авто"
    ReaderMode.On -> "Вкл"
    ReaderMode.Off -> "Выкл"
}

/** Чем озвучиваются события приложения: голосом TalkBack или собственным голосом. */
private enum class NarratorSource { TalkBack, App }

/** Настройки диктора. Хранятся в SharedPreferences вместе с остальными. */
private data class NarratorSettings(
    val source: NarratorSource = NarratorSource.TalkBack,
    val voiceName: String = "",
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
) {
    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putString("narrator_source", source.name)
            .putString("narrator_voice", voiceName)
            .putFloat("narrator_rate", rate)
            .putFloat("narrator_pitch", pitch)
            .apply()
    }

    companion object {
        fun load(prefs: SharedPreferences) = NarratorSettings(
            source = NarratorSource.entries
                .firstOrNull { it.name == prefs.getString("narrator_source", null) }
                ?: NarratorSource.TalkBack,
            voiceName = prefs.getString("narrator_voice", "") ?: "",
            rate = prefs.getFloat("narrator_rate", 1.0f),
            pitch = prefs.getFloat("narrator_pitch", 1.0f),
        )
    }
}

/**
 * Собственный голос приложения на системном синтезе речи (TextToSpeech).
 * Голос самого TalkBack он не меняет: тот настраивается только в системе.
 */
private class Narrator(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queued = ArrayList<() -> Unit>()

    private fun whenReady(action: () -> Unit) {
        if (ready) {
            action()
            return
        }
        queued += action
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    queued.toList().forEach { it() }
                }
                queued.clear()
            }
        }
    }

    fun speak(text: String, settings: NarratorSettings) = whenReady {
        val engine = tts ?: return@whenReady
        val voice = engine.voices.orEmpty().firstOrNull { it.name == settings.voiceName }
            ?: engine.defaultVoice
        if (voice != null) engine.voice = voice
        engine.setSpeechRate(settings.rate)
        engine.setPitch(settings.pitch)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tgws-narrator")
    }

    /** Голоса на русском и на языке системы, по порядку имён. */
    fun loadVoices(onLoaded: (List<Voice>) -> Unit) = whenReady {
        val language = Locale.getDefault().language
        onLoaded(
            tts?.voices.orEmpty()
                .filter { it.locale.language == "ru" || it.locale.language == language }
                .sortedBy { it.name }
        )
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        queued.clear()
    }
}

private fun voiceLabel(voice: Voice, index: Int): String {
    val ru = Locale("ru")
    val language = voice.locale.getDisplayLanguage(ru)
    val country = voice.locale.getDisplayCountry(ru)
    val place = if (country.isBlank()) language else "$language ($country)"
    val online = if (voice.isNetworkConnectionRequired) " · онлайн" else ""
    return "$place · голос ${index + 1}$online"
}

/** true, если режим для экранного диктора действует прямо сейчас. */
private val LocalScreenReaderMode = staticCompositionLocalOf { false }

/** Включён ли в системе экранный диктор (TalkBack и аналоги): следим за изменением на лету. */
@Composable
private fun rememberSystemScreenReader(): Boolean {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

private enum class AppScreen { Main, Log, Settings, Themes, Dictor, Updates, Terminal }

@Composable
private fun Screen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    palette: Palette,
    onPaletteChange: (Palette) -> Unit,
    readerMode: ReaderMode,
    onReaderModeChange: (ReaderMode) -> Unit,
    systemReader: Boolean,
    narratorSettings: NarratorSettings,
    onNarratorSettingsChange: (NarratorSettings) -> Unit,
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

    var customIps by remember { mutableStateOf(parseCustomIps(prefs.getString("custom_ips", null))) }

    fun runProbe() {
        ProxyRuntime.log("— проверка соединения —")
        prefs.edit().putString("worker", workerDomain).apply()
        RouteProbe.launch(
            hexToBytes(secretHex),
            parseDomains(workerDomain),
            log = { line -> ProxyRuntime.log(line) },
            customIps = customIps,
        )
        screen = AppScreen.Log
    }

    val view = LocalView.current
    val readerActive = LocalScreenReaderMode.current
    val narrator = remember { Narrator(context) }
    DisposableEffect(Unit) { onDispose { narrator.shutdown() } }
    fun announce(text: String) {
        if (narratorSettings.source == NarratorSource.App) {
            narrator.speak(text, narratorSettings)
        } else {
            view.announceForAccessibility(text)
        }
    }
    var lastRunning by remember { mutableStateOf(running) }
    LaunchedEffect(running) {
        if (running != lastRunning) {
            lastRunning = running
            if (readerActive) {
                announce(if (running) "Прокси запущен" else "Прокси остановлен")
            }
        }
    }
    LaunchedEffect(route) {
        if (readerActive && route != null) announce("Канал: $route")
    }

    val scope = rememberCoroutineScope()
    val versionName = remember { Updater.currentVersionName(context) }
    var updateInfo by remember { mutableStateOf<UpdateCheck.Available?>(null) }
    var downloadProgress by remember { mutableStateOf<Int?>(null) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var releaseNotes by remember { mutableStateOf<List<ReleaseNote>?>(null) }
    var notesLoading by remember { mutableStateOf(false) }
    var notesFailed by remember { mutableStateOf(false) }
    val versionCode = remember { Updater.currentVersionCode(context) }

    /** Возвращает текст ошибки или null, если адрес добавлен. */
    fun addCustomIp(rawIp: String, kind: IpKind): String? {
        val ip = rawIp.trim()
        ipError(ip)?.let { return it }
        val builtIn = TelegramDc.tcp.values + TelegramDc.wsRedirect.values + CLOUDFLARE_ANYCAST
        if (ip in builtIn) return "Этот адрес уже встроен в приложение"
        if (customIps.any { it.ip == ip }) return "Такой адрес уже добавлен"
        customIps = customIps + CustomIp(ip, kind)
        prefs.edit().putString("custom_ips", encodeCustomIps(customIps)).apply()
        return null
    }

    fun removeCustomIp(item: CustomIp) {
        customIps = customIps - item
        prefs.edit().putString("custom_ips", encodeCustomIps(customIps)).apply()
    }

    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
        }
    }

    fun openAccessibilitySettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(context, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
        }
    }

    fun openSpeechSettings() {
        try {
            context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: Exception) {
            openAccessibilitySettings()
        }
    }

    fun loadNotes() {
        if (releaseNotes != null || notesLoading) return
        notesLoading = true
        notesFailed = false
        scope.launch {
            val notes = Updater.loadReleaseNotes()
            releaseNotes = notes
            notesFailed = notes == null
            notesLoading = false
        }
    }

    fun checkUpdates(manual: Boolean) {
        if (checkingUpdates) return
        checkingUpdates = true
        scope.launch {
            val started = SystemClock.elapsedRealtime()
            val result = Updater.check(context)
            val took = " · ${"%.1f".format((SystemClock.elapsedRealtime() - started) / 1000.0)} с"
            when (result) {
                is UpdateCheck.Available -> {
                    updateInfo = result
                    updateStatus = "Доступна версия ${result.title}$took"
                }
                UpdateCheck.UpToDate -> if (manual) {
                    updateStatus = "Установлена последняя версия$took"
                }
                is UpdateCheck.Failed -> if (manual) {
                    updateStatus = "Не удалось проверить: ${result.reason}$took"
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
                    Text("Версия ${info.title}", fontWeight = FontWeight.SemiBold)
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

    BackHandler(enabled = screen != AppScreen.Main) {
        screen = if (
            screen == AppScreen.Themes || screen == AppScreen.Dictor || screen == AppScreen.Updates
        ) {
            AppScreen.Settings
        } else {
            AppScreen.Main
        }
    }

    if (screen == AppScreen.Settings) {
        SettingsScreen(
            themeMode = themeMode,
            palette = palette,
            onOpenThemes = { screen = AppScreen.Themes },
            versionName = versionName,
            updateAvailable = updateInfo != null,
            onOpenUpdates = { screen = AppScreen.Updates },
            dictorSummary = "${readerMode.label()} · " +
                if (narratorSettings.source == NarratorSource.App) "голос приложения" else "через TalkBack",
            onOpenDictor = { screen = AppScreen.Dictor },
            onBack = { screen = AppScreen.Main },
        )
        return
    }

    if (screen == AppScreen.Updates) {
        UpdatesScreen(
            versionName = versionName,
            versionCode = versionCode,
            checking = checkingUpdates,
            status = updateStatus,
            onCheck = { checkUpdates(manual = true) },
            notes = releaseNotes,
            notesLoading = notesLoading,
            notesFailed = notesFailed,
            onLoadNotes = { loadNotes() },
            onBack = { screen = AppScreen.Settings },
        )
        return
    }

    if (screen == AppScreen.Dictor) {
        DictorScreen(
            readerMode = readerMode,
            onReaderModeChange = onReaderModeChange,
            systemReader = systemReader,
            settings = narratorSettings,
            onSettingsChange = onNarratorSettingsChange,
            narrator = narrator,
            onOpenAccessibility = { openAccessibilitySettings() },
            onOpenSpeech = { openSpeechSettings() },
            onBack = { screen = AppScreen.Settings },
        )
        return
    }

    if (screen == AppScreen.Themes) {
        ThemesScreen(
            themeMode = themeMode,
            onThemeChange = onThemeChange,
            palette = palette,
            onPaletteChange = onPaletteChange,
            onBack = { screen = AppScreen.Settings },
        )
        return
    }

    if (screen == AppScreen.Terminal) {
        TerminalScreen(
            route = route,
            running = running,
            customIps = customIps,
            onAdd = { ip, kind -> addCustomIp(ip, kind) },
            onRemove = { item -> removeCustomIp(item) },
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
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
            IconButton(
                onClick = { screen = AppScreen.Settings },
                modifier = Modifier.semantics { contentDescription = "Настройки" }
            ) {
                Text("⚙", fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics { })
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
                onClick = { screen = AppScreen.Terminal },
                modifier = Modifier.weight(1f)
            ) {
                Text("Терминал")
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
    palette: Palette,
    onOpenThemes: () -> Unit,
    versionName: String,
    updateAvailable: Boolean,
    onOpenUpdates: () -> Unit,
    dictorSummary: String,
    onOpenDictor: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "Назад" }
            ) { Text("← Назад", modifier = Modifier.clearAndSetSemantics { }) }
            Text(
                "Настройки",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onOpenThemes,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Темы")
                    Text(
                        "${palette.label} · ${themeMode.label()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("›", fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics { })
            }

            OutlinedButton(
                onClick = onOpenDictor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Диктор")
                    Text(
                        dictorSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("›", fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics { })
            }

            OutlinedButton(
                onClick = onOpenUpdates,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Обновления")
                    Text(
                        if (updateAvailable) "Доступно обновление" else "Версия $versionName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("›", fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}

@Composable
private fun TermLine(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    bold: Boolean = false,
) {
    Text(
        text,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = color
    )
}

/** Терминал: поле как у журнала; своё IP вводится прямо в нижней строке поля. */
@Composable
private fun TerminalScreen(
    route: String?,
    running: Boolean,
    customIps: List<CustomIp>,
    onAdd: (String, IpKind) -> String?,
    onRemove: (CustomIp) -> Unit,
    onBack: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(IpKind.Gateway) }
    var note by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty()) return
        val problem = onAdd(text, kind)
        if (problem == null) {
            val kindName = if (kind == IpKind.Gateway) "шлюз Telegram" else "Cloudflare"
            note = "+ добавлен $text ($kindName)" to false
            input = ""
        } else {
            note = "! $problem" to true
        }
    }

    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "Назад" }
            ) { Text("← Назад", modifier = Modifier.clearAndSetSemantics { }) }
            Text(
                "Терминал",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
        }

        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            OptionSelector(
                title = "Новый адрес добавить как",
                options = listOf(
                    IpKind.Gateway to "Шлюз Telegram",
                    IpKind.Cloudflare to "Cloudflare",
                ),
                current = kind,
                onChange = { kind = it },
            )
        }

        // Поле терминала: тот же вид, что у журнала подключений.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TermLine("Сейчас: ${route ?: "нет активного подключения"}")
                Spacer(Modifier.height(8.dp))

                TermLine(
                    "# Мои адреса",
                    color = primary,
                    bold = true,
                    modifier = Modifier.semantics { heading() }
                )
                if (customIps.isEmpty()) {
                    TermLine("  (пока нет)")
                }
                customIps.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TermLine(
                            "  ${if (item.kind == IpKind.Gateway) "gw" else "cf"}  ${item.ip}",
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription = "${item.ip}, " +
                                        if (item.kind == IpKind.Gateway) "шлюз Telegram" else "Cloudflare"
                                },
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        TextButton(
                            onClick = { onRemove(item) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.semantics { contentDescription = "Удалить ${item.ip}" }
                        ) {
                            Text("✕", fontFamily = FontFamily.Monospace, modifier = Modifier.clearAndSetSemantics { })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                TermLine(
                    "# Встроенные адреса",
                    color = primary,
                    bold = true,
                    modifier = Modifier.semantics { heading() }
                )
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        TermLine("TCP, запасной путь и Worker:")
                        TelegramDc.tcp.entries.sortedBy { it.key }.forEach { (dc, ip) ->
                            TermLine("  ${"DC$dc".padEnd(10)}$ip", color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(Modifier.height(4.dp))
                        TermLine("WebSocket-шлюз Telegram:")
                        TelegramDc.wsRedirect.entries.groupBy({ it.value }, { it.key }).forEach { (ip, dcs) ->
                            val label = "DC${dcs.sorted().joinToString(", DC")}"
                            TermLine("  ${label.padEnd(10)}$ip", color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(Modifier.height(4.dp))
                        TermLine("Узлы Cloudflare (маскировка):")
                        CLOUDFLARE_ANYCAST.forEach { ip ->
                            TermLine("  $ip", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                TermLine(
                    if (running) {
                        "Прокси запущен: чтобы адреса заработали, остановите его и запустите снова."
                    } else {
                        "Адреса применятся при следующем запуске прокси."
                    }
                )
                TermLine(
                    "gw: шлюз Telegram (WebSocket), cf: узел Cloudflare. Тип выбирается переключателем выше."
                )
                note?.let { (text, isError) ->
                    Spacer(Modifier.height(4.dp))
                    TermLine(
                        text,
                        color = if (isError) MaterialTheme.colorScheme.error else primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }

            // Строка ввода внизу поля, как приглашение терминала.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (kind == IpKind.Gateway) "gw› " else "cf› ",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = primary,
                    modifier = Modifier.clearAndSetSemantics { }
                )
                BasicTextField(
                    value = input,
                    onValueChange = { value ->
                        input = value.filter { it.isDigit() || it == '.' }.take(15)
                        note = null
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Строка ввода IP-адреса" },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (input.isEmpty()) {
                                Text(
                                    "введите IP, например 104.19.0.1",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    color = dim.copy(alpha = 0.6f),
                                    modifier = Modifier.clearAndSetSemantics { }
                                )
                            }
                            inner()
                        }
                    }
                )
                TextButton(
                    onClick = { submit() },
                    modifier = Modifier.semantics { contentDescription = "Добавить адрес" }
                ) {
                    Text("↵", fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics { })
                }
            }
        }
    }
}

@Composable
private fun UpdatesScreen(
    versionName: String,
    versionCode: Long,
    checking: Boolean,
    status: String?,
    onCheck: () -> Unit,
    notes: List<ReleaseNote>?,
    notesLoading: Boolean,
    notesFailed: Boolean,
    onLoadNotes: () -> Unit,
    onBack: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (expanded) onLoadNotes() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "Назад" }
            ) { Text("← Назад", modifier = Modifier.clearAndSetSemantics { }) }
            Text(
                "Обновления",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Текущая версия: $versionName",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedButton(
                onClick = onCheck,
                enabled = !checking,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (checking) "Проверка…" else "Проверить обновления")
            }

            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто" }
                ) {
                    Text("Что нового", modifier = Modifier.weight(1f))
                    Text(if (expanded) "▴" else "▾", modifier = Modifier.clearAndSetSemantics { })
                }

                if (expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when {
                            notesLoading || (notes == null && !notesFailed) ->
                                Text("Загрузка…", style = MaterialTheme.typography.bodySmall)
                            notesFailed ->
                                Text(
                                    "Не удалось загрузить список изменений. Проверьте подключение к интернету.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            notes.isNullOrEmpty() ->
                                Text("Описаний пока нет.", style = MaterialTheme.typography.bodySmall)
                            else -> notes.orEmpty().forEach { note ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        (note.versionCode?.let { "Версия ${Updater.versionLabel(it)}" } ?: note.title) +
                                            if (note.versionCode == versionCode) " · установлена" else "",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        note.notes.ifBlank { "Без описания" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DictorScreen(
    readerMode: ReaderMode,
    onReaderModeChange: (ReaderMode) -> Unit,
    systemReader: Boolean,
    settings: NarratorSettings,
    onSettingsChange: (NarratorSettings) -> Unit,
    narrator: Narrator,
    onOpenAccessibility: () -> Unit,
    onOpenSpeech: () -> Unit,
    onBack: () -> Unit,
) {
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var voicesLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(settings.source) {
        if (settings.source == NarratorSource.App) {
            narrator.loadVoices { list ->
                voices = list
                voicesLoaded = true
            }
        }
    }
    var rate by remember(settings.rate) { mutableStateOf(settings.rate) }
    var pitch by remember(settings.pitch) { mutableStateOf(settings.pitch) }
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "Назад" }
            ) { Text("← Назад", modifier = Modifier.clearAndSetSemantics { }) }
            Text(
                "Диктор",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OptionSelector(
                title = "Режим для экранного диктора (TalkBack)",
                options = ReaderMode.entries.map { it to it.label() },
                current = readerMode,
                onChange = onReaderModeChange,
            )
            Text(
                if (systemReader) {
                    "TalkBack в системе включён: в режиме «Авто» приложение голосом сообщает " +
                        "о запуске и остановке прокси и о смене канала."
                } else {
                    "TalkBack в системе выключен. В режиме «Авто» дополнительные озвучки " +
                        "включатся сами, когда вы включите его в настройках телефона."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onOpenAccessibility,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Открыть настройки TalkBack")
            }

            OptionSelector(
                title = "Чем озвучивать события приложения",
                options = listOf(
                    NarratorSource.TalkBack to "TalkBack",
                    NarratorSource.App to "Голос приложения",
                ),
                current = settings.source,
                onChange = { onSettingsChange(settings.copy(source = it)) },
            )
            Text(
                if (settings.source == NarratorSource.TalkBack) {
                    "События читает TalkBack своим голосом. Голос, скорость и язык самого " +
                        "TalkBack меняются в системных настройках синтеза речи."
                } else {
                    "События читает само приложение выбранным ниже голосом. Голос самого " +
                        "TalkBack это не меняет: он настраивается в системных настройках."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onOpenSpeech,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Системные настройки синтеза речи")
            }

            if (settings.source == NarratorSource.App) {
                val currentLabel = voices.withIndex()
                    .firstOrNull { it.value.name == settings.voiceName }
                    ?.let { voiceLabel(it.value, it.index) }
                    ?: "По умолчанию"

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Голос",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box {
                        OutlinedButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(currentLabel, maxLines = 1, modifier = Modifier.weight(1f))
                            Text("▾", modifier = Modifier.clearAndSetSemantics { })
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("По умолчанию") },
                                onClick = {
                                    onSettingsChange(settings.copy(voiceName = ""))
                                    menuOpen = false
                                }
                            )
                            voices.forEachIndexed { index, voice ->
                                DropdownMenuItem(
                                    text = { Text(voiceLabel(voice, index)) },
                                    onClick = {
                                        onSettingsChange(settings.copy(voiceName = voice.name))
                                        menuOpen = false
                                    }
                                )
                            }
                        }
                    }
                    if (voicesLoaded && voices.isEmpty()) {
                        Text(
                            "Голоса не найдены. Установите синтез речи в системных настройках.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Скорость речи: ${"%.1f".format(rate)}×",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = rate,
                        onValueChange = { rate = it },
                        onValueChangeFinished = { onSettingsChange(settings.copy(rate = rate)) },
                        valueRange = 0.5f..2.0f,
                        steps = 14
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Тон голоса: ${"%.1f".format(pitch)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = pitch,
                        onValueChange = { pitch = it },
                        onValueChangeFinished = { onSettingsChange(settings.copy(pitch = pitch)) },
                        valueRange = 0.5f..2.0f,
                        steps = 14
                    )
                }

                Button(
                    onClick = { narrator.speak("Проверка озвучки. Прокси запущен.", settings) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Проверить озвучку")
                }
            }
        }
    }
}

@Composable
private fun ThemesScreen(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    palette: Palette,
    onPaletteChange: (Palette) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "Назад" }
            ) { Text("← Назад", modifier = Modifier.clearAndSetSemantics { }) }
            Text(
                "Темы",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OptionSelector(
                title = "Режим",
                options = ThemeMode.entries.map { it to it.label() },
                current = themeMode,
                onChange = onThemeChange,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Цветовая схема",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Palette.entries.forEach { item ->
                    val selected = item == palette
                    val rowModifier = Modifier
                        .fillMaxWidth()
                        .semantics { stateDescription = if (selected) "Выбрано" else "Не выбрано" }
                    val rowContent: @Composable RowScope.() -> Unit = {
                        Box(
                            Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(item.swatch)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(item.label, modifier = Modifier.weight(1f))
                        if (selected) {
                            Text("✓", modifier = Modifier.clearAndSetSemantics { })
                        }
                    }
                    if (selected) {
                        Button(
                            onClick = { onPaletteChange(item) },
                            modifier = rowModifier,
                            content = rowContent
                        )
                    } else {
                        OutlinedButton(
                            onClick = { onPaletteChange(item) },
                            modifier = rowModifier,
                            content = rowContent
                        )
                    }
                }
            }

            Text(
                "Схема «AMOLED» меняет только тёмный режим: в светлом используется стандартная.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun <T> OptionSelector(
    title: String,
    options: List<Pair<T, String>>,
    current: T,
    onChange: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (value, label) ->
                val padding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                val selected = value == current
                val buttonModifier = Modifier
                    .weight(1f)
                    .semantics { stateDescription = if (selected) "Выбрано" else "Не выбрано" }
                if (selected) {
                    Button(
                        onClick = { onChange(value) },
                        contentPadding = padding,
                        modifier = buttonModifier
                    ) { Text(label, maxLines = 1) }
                } else {
                    OutlinedButton(
                        onClick = { onChange(value) },
                        contentPadding = padding,
                        modifier = buttonModifier
                    ) { Text(label, maxLines = 1) }
                }
            }
        }
    }
}

/** Кнопка панели действий журнала: одинаковая высота, обводка, подпись в 1–2 строки. */
@Composable
private fun LogActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Text(
            label,
            fontSize = 14.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
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
            TextButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "Назад" }
            ) { Text("← Назад", modifier = Modifier.clearAndSetSemantics { }) }
            Text(
                "Журнал подключений",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
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
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LogActionButton("Проверить связь", onProbe, Modifier.weight(1f))
            LogActionButton("Копировать", onCopy, Modifier.weight(1f))
            LogActionButton("Очистить", onClear, Modifier.weight(1f))
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
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
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
