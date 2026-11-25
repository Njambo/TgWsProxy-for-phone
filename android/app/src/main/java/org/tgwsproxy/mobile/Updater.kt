package org.tgwsproxy.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data object Failed : UpdateCheck
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
 * и приложенным APK. Приложение сравнивает свой versionCode с номером в теге
 * последнего релиза.
 */
object Updater {
    const val REPO = "Njambo/TgWsProxy-for-phone"

    private val TAG_RE = Regex("""build-(\d+)""")

    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info)
    }

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    suspend fun check(context: Context): UpdateCheck = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return@withContext UpdateCheck.Failed

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val remote = TAG_RE.matchEntire(tag)?.groupValues?.get(1)?.toLongOrNull()
                ?: return@withContext UpdateCheck.Failed
            if (remote <= currentVersionCode(context)) return@withContext UpdateCheck.UpToDate

            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url").ifBlank { null }
                        break
                    }
                }
            }
            UpdateCheck.Available(
                versionCode = remote,
                title = json.optString("name").ifBlank { tag },
                notes = json.optString("body"),
                apkUrl = apkUrl,
                pageUrl = json.optString("html_url")
                    .ifBlank { "https://github.com/$REPO/releases/latest" },
            )
        } catch (_: Exception) {
            UpdateCheck.Failed
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
