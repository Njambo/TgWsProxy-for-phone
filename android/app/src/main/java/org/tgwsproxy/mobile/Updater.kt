package org.tgwsproxy.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Failed(val reason: String) : UpdateCheck
    data class Available(
        val versionCode: Long,
        val title: String,
        val notes: String,
        val apkUrl: String?,
        val pageUrl: String,
    ) : UpdateCheck
}

/**
 * Проверка обновлений через GitHub Releases.
 *
 * CI публикует каждую сборку из main как релиз с тегом `build-<versionCode>`
 * и APK `tgws-mobile-<versionCode>.apk`. Приложение открывает страницу
 * `/releases/latest`, не следует за перенаправлением и берёт номер сборки из
 * адреса, на который она ведёт. API GitHub не используется: у него жёсткий
 * лимит 60 запросов в час на IP, а за одним IP часто сидят тысячи абонентов.
 */
object Updater {
    const val REPO = "Njambo/TgWsProxy-for-phone"

    private val TAG_RE = Regex("""/releases/tag/build-(\d+)""")

    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info)
    }

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    suspend fun check(context: Context): UpdateCheck = withContext(Dispatchers.IO) {
        val conn = URL("https://github.com/$REPO/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "TgWsProxyMobile")

            val code = conn.responseCode
            if (code !in 300..399) {
                return@withContext UpdateCheck.Failed("ответ сервера $code")
            }
            val location = conn.getHeaderField("Location")
                ?: return@withContext UpdateCheck.Failed("нет адреса релиза")
            val remote = TAG_RE.find(location)?.groupValues?.get(1)?.toLongOrNull()
                ?: return@withContext UpdateCheck.Failed("релизов пока нет")

            if (remote <= currentVersionCode(context)) return@withContext UpdateCheck.UpToDate

            val base = "https://github.com/$REPO/releases"
            UpdateCheck.Available(
                versionCode = remote,
                title = "Сборка $remote",
                notes = "",
                apkUrl = "$base/download/build-$remote/tgws-mobile-$remote.apk",
                pageUrl = "$base/tag/build-$remote",
            )
        } catch (e: Exception) {
            val detail = e.message?.take(80) ?: ""
            UpdateCheck.Failed("${e.javaClass.simpleName} $detail".trim())
        } finally {
            conn.disconnect()
        }
    }

    /** Скачивает APK во внутренний кэш приложения. onProgress получает 0..100. */
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val file = File(dir, "update.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.connect()
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Файл скачан не полностью")
            file
        } finally {
            conn.disconnect()
        }
    }

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Открывает системный установщик. Android всегда просит подтверждение у пользователя. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
