package org.tgwsproxy.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.tgwsproxy.mobile.core.ProxyServer
import org.tgwsproxy.mobile.core.parseCustomIps
import org.tgwsproxy.mobile.core.parseDomains
import kotlin.concurrent.thread

class ProxyService : Service() {

    private var server: ProxyServer? = null

    @Volatile
    private var pythonRunning = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        val secretHex = intent?.getStringExtra(EXTRA_SECRET)
        val workerDomains = parseDomains(intent?.getStringExtra(EXTRA_WORKER))
        val customIps = parseCustomIps(
            getSharedPreferences("proxy", Context.MODE_PRIVATE).getString("custom_ips", null)
        )
        if (secretHex == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(port)

        server?.stop()
        server = null
        if (pythonRunning) {
            pythonRunning = false
            PythonEngine.stop()
        }

        ProxyRuntime.port.value = port
        ProxyRuntime.secretHex.value = secretHex
        ProxyRuntime.setRoute(null)
        ProxyRuntime.setStatus(ProxyStatus.Running(port))

        val prefs = getSharedPreferences("proxy", Context.MODE_PRIVATE)
        if (prefs.getString("engine", "native") == "python") {
            startPython(port, secretHex, workerDomains.joinToString(","), prefs)
            return START_NOT_STICKY
        }

        val instance = ProxyServer(
            port = port,
            secret = hexToBytes(secretHex),
            workerDomains = workerDomains,
            customIps = customIps,
            onRoute = { route -> ProxyRuntime.setRoute(route) },
        ) { line -> ProxyRuntime.log(line) }
        server = instance
        if (customIps.isNotEmpty()) ProxyRuntime.log("Свои IP-адреса: ${customIps.size}")
        try {
            instance.start()
        } catch (exception: Exception) {
            ProxyRuntime.log("Не удалось запустить: ${exception.message}")
            ProxyRuntime.setStatus(ProxyStatus.Stopped)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    /** Оригинальный TG WS Proxy на встроенном Python (Настройки → Движок). */
    private fun startPython(port: Int, secretHex: String, workerDomains: String, prefs: SharedPreferences) {
        pythonRunning = true
        ProxyRuntime.log("Движок: оригинальный (Python)")
        val settings = PythonEngine.Settings(
            port = port,
            secretHex = secretHex,
            workerDomains = workerDomains,
            userDomains = prefs.getString("py_domains", "") ?: "",
            bufKb = prefs.getInt("py_buf", 256).coerceIn(4, 4096),
            poolSize = prefs.getInt("py_pool", 4).coerceIn(0, 16),
            cfproxy = prefs.getBoolean("py_cfproxy", true),
            h2 = prefs.getBoolean("py_h2", true),
            noSecure = prefs.getBoolean("py_nosecure", false),
            verbose = prefs.getBoolean("py_verbose", false),
        )
        val appContext = applicationContext
        val sink = ProxyLogSink(
            onLine = { line -> ProxyRuntime.log(line) },
            onStop = { error ->
                if (error != null) ProxyRuntime.log("Python-движок остановлен: $error")
                if (pythonRunning) {
                    pythonRunning = false
                    ProxyRuntime.setStatus(ProxyStatus.Stopped)
                    stopSelf()
                }
            },
        )
        thread(name = "python-start") {
            try {
                PythonEngine.start(appContext, settings, sink)
            } catch (t: Throwable) {
                ProxyRuntime.log("Не удалось запустить Python-движок: ${t.javaClass.simpleName}: ${t.message}")
                pythonRunning = false
                ProxyRuntime.setStatus(ProxyStatus.Stopped)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        if (pythonRunning) {
            pythonRunning = false
            PythonEngine.stop()
        }
        ProxyRuntime.setStatus(ProxyStatus.Stopped)
        ProxyRuntime.setRoute(null)
        ProxyRuntime.log("Сервис остановлен")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(port: Int) {
        val notification = buildNotification(port)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(port: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("127.0.0.1:$port")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1443
        const val EXTRA_PORT = "port"
        const val EXTRA_SECRET = "secret"
        const val EXTRA_WORKER = "worker"
        const val DEFAULT_PORT = 1443

        fun start(context: Context, port: Int, secretHex: String, workerDomain: String) {
            val intent = Intent(context, ProxyService::class.java)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_SECRET, secretHex)
                .putExtra(EXTRA_WORKER, workerDomain)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyService::class.java))
        }
    }
}
