package org.tgwsproxy.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.tgwsproxy.mobile.core.ProxyServer

class ProxyService : Service() {

    private var server: ProxyServer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        val secretHex = intent?.getStringExtra(EXTRA_SECRET)
        if (secretHex == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(port)

        server?.stop()
        server = null

        ProxyRuntime.port.value = port
        ProxyRuntime.secretHex.value = secretHex
        ProxyRuntime.setStatus(ProxyStatus.Running(port))

        val instance = ProxyServer(port, hexToBytes(secretHex)) { line -> ProxyRuntime.log(line) }
        server = instance
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

    override fun onDestroy() {
        server?.stop()
        server = null
        ProxyRuntime.setStatus(ProxyStatus.Stopped)
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
        const val DEFAULT_PORT = 1443

        fun start(context: Context, port: Int, secretHex: String) {
            val intent = Intent(context, ProxyService::class.java)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_SECRET, secretHex)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyService::class.java))
        }
    }
}
