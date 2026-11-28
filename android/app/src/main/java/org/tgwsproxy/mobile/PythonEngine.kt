package org.tgwsproxy.mobile

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** Через этот интерфейс оригинальный движок на Python присылает журнал и сообщает об остановке. */
interface PyLogSink {
    fun log(line: String)
    fun onStopped(error: String?)
}

/** Публичный класс, чтобы Python мог вызывать его методы. */
class ProxyLogSink(
    private val onLine: (String) -> Unit,
    private val onStop: (String?) -> Unit,
) : PyLogSink {
    override fun log(line: String) = onLine(line)
    override fun onStopped(error: String?) = onStop(error)
}

/** Оригинальный TG WS Proxy (папка python/proxy), запущенный на встроенном Python. */
object PythonEngine {
    data class Settings(
        val port: Int,
        val secretHex: String,
        val workerDomains: String,
        val userDomains: String,
        val bufKb: Int,
        val poolSize: Int,
        val cfproxy: Boolean,
        val h2: Boolean,
        val noSecure: Boolean,
        val verbose: Boolean,
    )

    private const val DC_IP = "2:149.154.167.220,4:149.154.167.220"

    private fun module(context: Context): com.chaquo.python.PyObject {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        return Python.getInstance().getModule("tgws_android")
    }

    /** Блокирует поток на время запуска интерпретатора: вызывать не из основного потока. */
    fun start(context: Context, settings: Settings, sink: PyLogSink) {
        module(context).callAttr(
            "start", sink, settings.port, settings.secretHex, DC_IP, settings.bufKb, settings.poolSize,
            settings.cfproxy, settings.h2, settings.userDomains, settings.workerDomains,
            settings.noSecure, settings.verbose
        )
    }

    fun stop() {
        if (!Python.isStarted()) return
        Python.getInstance().getModule("tgws_android").callAttr("stop")
    }
}
