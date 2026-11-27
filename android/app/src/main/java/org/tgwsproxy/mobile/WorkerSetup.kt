package org.tgwsproxy.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Создаёт Worker в аккаунте Cloudflare самого пользователя через API Cloudflare.
 *
 * Нужен API-токен с правом править Workers (шаблон «Edit Cloudflare Workers»). Токен
 * используется только для запросов к api.cloudflare.com и нигде не сохраняется.
 * Код Worker берётся из assets (это файл docs/cloudflare-worker.js репозитория).
 */
object WorkerSetup {
    private const val API = "https://api.cloudflare.com/client/v4"
    private val NAME_RE = Regex("^[a-z0-9][a-z0-9-]{0,53}$")

    /** Ошибка с понятным пользователю текстом. */
    class SetupException(message: String) : Exception(message)

    private class ApiException(val http: Int, message: String) : Exception(message)

    /** Возвращает адрес вида имя.поддомен.workers.dev; ход работы пишет в [log]. */
    suspend fun run(
        context: Context,
        token: String,
        scriptName: String,
        desiredSubdomain: String,
        log: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        if (token.isBlank()) throw SetupException("Вставьте API-токен Cloudflare.")
        if (!NAME_RE.matches(scriptName)) {
            throw SetupException("Имя Worker: латинские буквы, цифры и дефис, до 54 символов.")
        }
        if (desiredSubdomain.isNotEmpty() && !NAME_RE.matches(desiredSubdomain)) {
            throw SetupException("Поддомен: латинские буквы, цифры и дефис, до 54 символов.")
        }
        try {
            log("Проверяю токен…")
            val accounts = call("GET", "/accounts", token).optJSONArray("result")
            if (accounts == null || accounts.length() == 0) {
                throw SetupException("У токена нет доступа ни к одному аккаунту Cloudflare.")
            }
            val accountId = accounts.getJSONObject(0).getString("id")
            log("Аккаунт найден.")

            var subdomain = readSubdomain(accountId, token)
            if (subdomain == null) {
                if (desiredSubdomain.isEmpty()) {
                    throw SetupException(
                        "У аккаунта ещё нет адреса workers.dev. Впишите любое имя поддомена " +
                            "в поле выше и повторите."
                    )
                }
                log("Создаю поддомен $desiredSubdomain.workers.dev…")
                call(
                    "PUT", "/accounts/$accountId/workers/subdomain", token,
                    """{"subdomain":"$desiredSubdomain"}""".toByteArray(), "application/json"
                )
                subdomain = desiredSubdomain
            }

            log("Загружаю код Worker…")
            upload(context, accountId, scriptName, token)

            log("Включаю адрес workers.dev…")
            call(
                "POST", "/accounts/$accountId/workers/scripts/$scriptName/subdomain", token,
                """{"enabled":true}""".toByteArray(), "application/json"
            )

            val address = "$scriptName.$subdomain.workers.dev"
            log("Жду, пока адрес заработает…")
            if (waitUntilLive(address)) {
                log("Адрес отвечает.")
            } else {
                log("Адрес пока не отвечает. Это нормально, подождите минуту.")
            }
            address
        } catch (e: ApiException) {
            throw SetupException(explain(e))
        }
    }

    private fun readSubdomain(accountId: String, token: String): String? = try {
        call("GET", "/accounts/$accountId/workers/subdomain", token)
            .optJSONObject("result")?.optString("subdomain")?.takeIf { it.isNotBlank() }
    } catch (e: ApiException) {
        if (e.http == 404) null else throw e
    }

    private fun upload(context: Context, accountId: String, scriptName: String, token: String) {
        val code = try {
            context.assets.open("cloudflare-worker.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: IOException) {
            throw SetupException("Код Worker не найден в приложении.")
        }
        val boundary = "----tgws" + System.currentTimeMillis()
        val body = ByteArrayOutputStream()
        fun part(name: String, fileName: String?, type: String, data: ByteArray) {
            body.write("--$boundary\r\n".toByteArray())
            val file = if (fileName != null) "; filename=\"$fileName\"" else ""
            body.write("Content-Disposition: form-data; name=\"$name\"$file\r\n".toByteArray())
            body.write("Content-Type: $type\r\n\r\n".toByteArray())
            body.write(data)
            body.write("\r\n".toByteArray())
        }
        part(
            "metadata", null, "application/json",
            """{"main_module":"worker.js","compatibility_date":"2024-11-01"}""".toByteArray()
        )
        part("worker.js", "worker.js", "application/javascript+module", code.toByteArray(Charsets.UTF_8))
        body.write("--$boundary--\r\n".toByteArray())
        call(
            "PUT", "/accounts/$accountId/workers/scripts/$scriptName", token,
            body.toByteArray(), "multipart/form-data; boundary=$boundary"
        )
    }

    private suspend fun waitUntilLive(address: String): Boolean {
        repeat(6) {
            try {
                val conn = URL("https://$address/").openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 5_000
                    val code = conn.responseCode
                    if (code != 404 && code < 500) return true
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
            delay(2_000)
        }
        return false
    }

    private fun call(
        method: String,
        path: String,
        token: String,
        body: ByteArray? = null,
        contentType: String = "application/json",
    ): JSONObject {
        val conn = URL(API + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                JSONObject()
            }
            if (code !in 200..299 || !json.optBoolean("success", true)) {
                throw ApiException(code, errorText(json) ?: "HTTP $code")
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun errorText(json: JSONObject): String? {
        val first = json.optJSONArray("errors")?.optJSONObject(0) ?: return null
        return "${first.optString("message")} (код ${first.optInt("code")})"
    }

    private fun explain(e: ApiException): String = when (e.http) {
        401, 403 ->
            "Cloudflare отклонил токен или у него не хватает прав (${e.message}). " +
                "Создайте токен по шаблону «Edit Cloudflare Workers»."
        else -> "Cloudflare ответил ошибкой: ${e.message}"
    }
}
