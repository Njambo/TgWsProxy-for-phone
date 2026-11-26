package org.tgwsproxy.mobile.core

/** Что за адрес добавил пользователь: шлюз Telegram или узел Cloudflare. */
internal enum class IpKind { Gateway, Cloudflare }

internal data class CustomIp(val ip: String, val kind: IpKind)

/** Хранится построчно: «G 1.2.3.4» — шлюз Telegram, «C 1.2.3.4» — Cloudflare. */
internal fun encodeCustomIps(list: List<CustomIp>): String =
    list.joinToString("\n") { item ->
        "${if (item.kind == IpKind.Gateway) "G" else "C"} ${item.ip}"
    }

internal fun parseCustomIps(raw: String?): List<CustomIp> = raw.orEmpty()
    .lineSequence()
    .mapNotNull { line ->
        val parts = line.trim().split(' ')
        if (parts.size != 2) return@mapNotNull null
        val kind = when (parts[0]) {
            "G" -> IpKind.Gateway
            "C" -> IpKind.Cloudflare
            else -> return@mapNotNull null
        }
        if (ipError(parts[1]) != null) return@mapNotNull null
        CustomIp(parts[1], kind)
    }
    .distinct()
    .toList()

/** null, если адрес подходит; иначе текст ошибки для пользователя. */
internal fun ipError(text: String): String? {
    val parts = text.trim().split('.')
    val wrongShape = parts.size != 4 ||
        parts.any { part -> part.isEmpty() || part.length > 3 || !part.all { c -> c.isDigit() } }
    if (wrongShape) return "Введите IPv4-адрес вида 1.2.3.4"
    val n = parts.map { it.toInt() }
    if (n.any { it > 255 }) return "Каждое число в адресе должно быть от 0 до 255"
    val a = n[0]
    val b = n[1]
    val local = a == 0 || a == 10 || a == 127 || a >= 224 ||
        (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) || (a == 100 && b in 64..127)
    if (local) return "Этот адрес не подходит: он локальный или служебный"
    return null
}
