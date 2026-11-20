package org.tgwsproxy.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

sealed interface ProxyStatus {
    data object Stopped : ProxyStatus
    data class Running(val port: Int) : ProxyStatus
}

object ProxyRuntime {
    val status = MutableStateFlow<ProxyStatus>(ProxyStatus.Stopped)
    val logs = MutableStateFlow<List<String>>(emptyList())
    val port = MutableStateFlow(1443)
    val secretHex = MutableStateFlow(randomSecretHex())

    /** Label of the route the last successful Telegram connection used. */
    val route = MutableStateFlow<String?>(null)

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun setStatus(value: ProxyStatus) {
        status.value = value
    }

    fun setRoute(value: String?) {
        route.value = value
    }

    @Synchronized
    fun log(line: String) {
        val stamp = timeFormat.format(LocalTime.now())
        logs.value = (logs.value + "[$stamp] $line").takeLast(500)
    }

    @Synchronized
    fun clearLogs() {
        logs.value = emptyList()
    }

    fun snapshot(): String = logs.value.joinToString("\n")
}
