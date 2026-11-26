package org.tgwsproxy.mobile.core

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/** Failure tagged with the stage it happened at: TCP, DNS, TLS or WS. */
internal class StageError(val stage: String, message: String) : IOException(message)

/** Cloudflare anycast edges that the camouflage routes dial directly. */
internal val CLOUDFLARE_ANYCAST = listOf(
    "104.16.0.1",
    "104.17.0.1",
    "104.18.0.1",
    "188.114.96.1",
)

/** Telegram DC entry points. Mirrors the reference proxy's tables. */
internal object TelegramDc {
    val tcp: Map<Int, String> = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "149.154.171.5",
        203 to "91.105.192.100",
    )

    /** DCs served by the fixed WebSocket gateway IP (DC2 and DC4 only). */
    val wsRedirect: Map<Int, String> = mapOf(
        2 to "149.154.167.220",
        4 to "149.154.167.220",
    )
}

/** One way of reaching the Telegram WebSocket gateway. */
internal class WsRoute(
    val label: String,
    val dial: String,
    val sni: String,
    val hostHeader: String,
    val verifyHostname: Boolean = true,
    val timeoutMs: Int = 8_000,
    val port: Int = 443,
    val secure: Boolean = true,
    val path: String = "/apiws",
    /**
     * The far end pipes raw bytes (our Worker) instead of Telegram's WS
     * gateway, so one frame per MTProto packet is pure overhead there.
     */
    val rawStream: Boolean = false,
)

/**
 * Picks the route to Telegram's WS gateway, following the reference proxy.
 *
 * The direct path always dials the pinned gateway IP with the `kwsN` name as
 * SNI and Host — never a DNS lookup of that name. Then SNI fronting, then the
 * Cloudflare camouflage domains over TLS, then the same domains over plain
 * HTTP on port 80 (DPI sees no SNI there, only a Host header).
 *
 * A full round with no winner arms a backoff for that DC, so a blocked
 * network costs one slow discovery instead of one per Telegram reconnect.
 */
internal class TransportRouter(
    private val log: (String) -> Unit,
    private val pool: ExecutorService,
    private val workerDomains: List<String> = emptyList(),
    private val onRoute: (String?) -> Unit = {},
    customIps: List<CustomIp> = emptyList(),
) {
    /** Адреса, добавленные пользователем на экране «Терминал». */
    private val customGateway = customIps.filter { it.kind == IpKind.Gateway }.map { it.ip }
    private val customCloudflare = customIps.filter { it.kind == IpKind.Cloudflare }.map { it.ip }

    private val preferred = ConcurrentHashMap<String, WsRoute>()
    private val backoffUntil = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, Int>()

    private val lock = Object()
    private var discovering = false

    fun open(dc: Int, media: Boolean): RawWebSocket? {
        val key = "$dc/$media"
        val now = System.currentTimeMillis()
        val blockedUntil = backoffUntil[key] ?: 0L
        if (now < blockedUntil) {
            log("Сеть блокирует Telegram · повтор через ${(blockedUntil - now) / 1000 + 1} с")
            return null
        }

        preferred[key]?.let { route ->
            val socket = attempt(route)
            if (socket != null) return socket
            log("Маршрут «${route.label}» перестал работать, ищу заново")
            preferred.remove(key)
            onRoute(null)
        }

        if (!beginDiscovery()) {
            awaitDiscovery(RACE_BUDGET_MS)
            return preferred[key]?.let { route -> attempt(route) }
        }

        try {
            if (workerDomains.isNotEmpty()) {
                val own = race(key, workerRoutes(dc), WORKER_BUDGET_MS)
                if (own != null) {
                    failures.remove(key)
                    backoffUntil.remove(key)
                    return own
                }
                log("Свой Worker не ответил, пробую общие маршруты…")
            }
            var socket = race(
                key,
                directRoutes(dc, media) +
                    cfRoutes(dc, media, secure = true) +
                    cfIpRoutes(dc, media, secure = true),
                RACE_BUDGET_MS,
            )
            if (socket == null) {
                log("HTTPS-маршруты не ответили, пробую порт 80…")
                socket = race(
                    key,
                    cfRoutes(dc, media, secure = false) +
                        cfIpRoutes(dc, media, secure = false),
                    PLAIN_BUDGET_MS,
                )
            }
            if (socket == null) {
                val count = (failures[key] ?: 0) + 1
                failures[key] = count
                val delay = minOf(5_000L shl minOf(count - 1, 4), 60_000L)
                backoffUntil[key] = System.currentTimeMillis() + delay
                log(
                    "Ни один маршрут не ответил · сеть блокирует и Telegram, " +
                        "и Cloudflare · пауза ${delay / 1000} с"
                )
                return null
            }
            failures.remove(key)
            backoffUntil.remove(key)
            return socket
        } finally {
            endDiscovery()
        }
    }

    /** Best-effort connection without arming the backoff — used by the self-test. */
    fun probe(dc: Int, media: Boolean): RawWebSocket? {
        val key = "probe/$dc/$media"
        if (workerDomains.isNotEmpty()) {
            race(key, workerRoutes(dc), WORKER_BUDGET_MS)?.let { return it }
            log("Свой Worker не ответил, пробую общие маршруты…")
        }
        return race(
            key,
            directRoutes(dc, media) +
                cfRoutes(dc, media, secure = true) +
                cfIpRoutes(dc, media, secure = true),
            RACE_BUDGET_MS,
        ) ?: race(
            key,
            cfRoutes(dc, media, secure = false) + cfIpRoutes(dc, media, secure = false),
            PLAIN_BUDGET_MS,
        )
    }

    private fun race(key: String, routes: List<WsRoute>, budgetMs: Long): RawWebSocket? {
        if (routes.isEmpty()) return null
        val winner = AtomicReference<RawWebSocket?>(null)
        val remaining = AtomicInteger(routes.size)
        val latch = CountDownLatch(1)

        for (route in routes) {
            val task = Runnable {
                try {
                    val socket = attempt(route)
                    if (socket != null) {
                        if (winner.compareAndSet(null, socket)) {
                            preferred[key] = route
                            log("Маршрут: ${route.label}")
                            onRoute(route.label)
                            latch.countDown()
                        } else {
                            socket.close()
                        }
                    }
                } finally {
                    if (remaining.decrementAndGet() == 0) latch.countDown()
                }
            }
            try {
                pool.execute(task)
            } catch (_: Exception) {
                if (remaining.decrementAndGet() == 0) latch.countDown()
            }
        }

        latch.await(budgetMs, TimeUnit.MILLISECONDS)
        return winner.get()
    }

    /** Pinned gateway IPs, plain and fronted: the built-in one plus the user's own. */
    private fun directRoutes(dc: Int, media: Boolean): List<WsRoute> {
        val routes = ArrayList<WsRoute>()
        TelegramDc.wsRedirect[dc]?.let { routes += gatewayRoutes(it, dc, media, own = false) }
        for (ip in customGateway) routes += gatewayRoutes(ip, dc, media, own = true)
        return routes
    }

    /** One gateway IP with the `kwsN` names as SNI/Host. No DNS lookup of the name. */
    private fun gatewayRoutes(ip: String, dc: Int, media: Boolean, own: Boolean): List<WsRoute> {
        val index = if (dc == 203) 2 else dc
        val names = if (media) {
            listOf("kws$index-1.web.telegram.org", "kws$index.web.telegram.org")
        } else {
            listOf("kws$index.web.telegram.org")
        }
        val tag = if (own) "Свой IP" else "IP"
        val fronting = if (own) "SNI-маскировка $FRONTING_SNI → $ip" else "SNI-маскировка $FRONTING_SNI"
        val routes = ArrayList<WsRoute>(names.size * 2)
        for (name in names) {
            routes += WsRoute("$tag $ip ($name)", ip, name, name, timeoutMs = 8_000)
            routes += WsRoute(
                "$fronting ($name)",
                ip, FRONTING_SNI, name,
                verifyHostname = false, timeoutMs = 7_000,
            )
        }
        return routes
    }

    private fun cfRoutes(dc: Int, media: Boolean, secure: Boolean): List<WsRoute> {
        val index = if (dc == 203) 2 else dc
        val prefix = if (media) "kws$index-1" else "kws$index"
        return CAMOUFLAGE_DOMAINS.map { domain ->
            val name = "$prefix.$domain"
            if (secure) {
                WsRoute("CF $name", name, name, name, timeoutMs = 9_000)
            } else {
                WsRoute("CF80 $name", name, name, name, timeoutMs = 9_000, port = 80, secure = false)
            }
        }
    }

    /**
     * Same Cloudflare path, but dialling fixed anycast IPs: no DNS lookup at
     * all, so a poisoned or filtered resolver cannot stop the route. Any
     * Cloudflare IP serves any hosted zone, so the SNI/Host pair still picks
     * the right backend and the certificate matches the camouflage name.
     */
    private fun cfIpRoutes(dc: Int, media: Boolean, secure: Boolean): List<WsRoute> {
        val index = if (dc == 203) 2 else dc
        val prefix = if (media) "kws$index-1" else "kws$index"
        val routes = ArrayList<WsRoute>(CF_ANYCAST.size * CF_IP_DOMAINS.size)
        for (domain in CF_IP_DOMAINS) {
            val name = "$prefix.$domain"
            for (ip in CF_ANYCAST + customCloudflare) {
                if (secure) {
                    routes += WsRoute("CF-IP $ip ($name)", ip, name, name, timeoutMs = 9_000)
                } else {
                    routes += WsRoute(
                        "CF80-IP $ip ($name)", ip, name, name,
                        timeoutMs = 9_000, port = 80, secure = false,
                    )
                }
            }
        }
        return routes
    }

    /**
     * A Cloudflare Worker deployed by the user. The worker opens a TCP
     * connection to `dst` itself, so the path carries the DC address and id.
     * Unlike the shared camouflage domains this one is the user's own and is
     * not in any blocked list.
     */
    private fun workerRoutes(dc: Int): List<WsRoute> {
        val dst = TelegramDc.tcp[dc] ?: return emptyList()
        return workerDomains.map { domain ->
            WsRoute(
                label = "Worker $domain",
                dial = domain,
                sni = domain,
                hostHeader = domain,
                path = "/apiws?dst=$dst&dc=$dc",
                timeoutMs = 10_000,
                rawStream = true,
            )
        }
    }

    private fun attempt(route: WsRoute): RawWebSocket? = try {
        RawWebSocket.connect(
            host = route.dial,
            sni = route.sni,
            hostHeader = route.hostHeader,
            path = route.path,
            connectTimeoutMs = route.timeoutMs,
            verifyHostname = route.verifyHostname,
            port = route.port,
            secure = route.secure,
        ).also { socket -> socket.byteStream = route.rawStream }
    } catch (exception: Exception) {
        log("× ${route.label}: ${shortError(exception)}")
        null
    }

    private fun beginDiscovery(): Boolean = synchronized(lock) {
        if (discovering) {
            false
        } else {
            discovering = true
            true
        }
    }

    private fun endDiscovery() = synchronized(lock) {
        discovering = false
        lock.notifyAll()
    }

    private fun awaitDiscovery(budgetMs: Long) = synchronized(lock) {
        if (discovering) lock.wait(budgetMs)
    }

    private companion object {
        const val RACE_BUDGET_MS = 9_000L
        const val PLAIN_BUDGET_MS = 8_000L
        const val WORKER_BUDGET_MS = 10_000L
        const val FRONTING_SNI = "sprinthost.ru"

        /** Cloudflare-fronted aliases of the WS gateway, as in tg-ws-proxy. */
        val CAMOUFLAGE_DOMAINS = listOf(
            "pclead.co.uk",
            "offshor.co.uk",
            "cakeisalie.co.uk",
            "noskomnadzor.co.uk",
            "lovetrue.co.uk",
            "sorokdva.co.uk",
            "pyatdesyatdva.co.uk",
            "kartoshka.co.uk",
            "sorokodin.co.uk",
            "pyatdesyatodin.co.uk",
            "notelega.co.uk",
            "ebally.co.uk",
            "nebally.co.uk",
            "havegreatday.co.uk",
            "pomogite.co.uk",
            "fixtelega.co.uk",
            "sadnews.co.uk",
            "onedaychamp.co.uk",
            "stopblocking.co.uk",
            "nothingthere.co.uk",
        )

        /** Any Cloudflare anycast edge serves any hosted zone (SNI picks it). */
        val CF_ANYCAST = CLOUDFLARE_ANYCAST

        /** Camouflage zones dialled by fixed IP, so no DNS is involved. */
        val CF_IP_DOMAINS = listOf(
            "pclead.co.uk",
            "cakeisalie.co.uk",
            "nothingthere.co.uk",
        )
    }
}

/** Splits a user-typed list of Worker or proxy domains. */
internal fun parseDomains(raw: String?): List<String> = raw.orEmpty()
    .split(',', ' ', ';', '\n')
    .map { part ->
        part.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .trimEnd('.')
            .lowercase()
    }
    .filter { it.isNotEmpty() }
    .distinct()

internal fun shortError(exception: Exception): String = when (exception) {
    is StageError -> "${exception.stage}: ${exception.message?.take(90) ?: "ошибка"}"
    is SocketTimeoutException -> "таймаут"
    is UnknownHostException -> "DNS не отвечает"
    is ConnectException -> "нет соединения"
    is SSLException -> "TLS: ${exception.message?.take(90) ?: "ошибка"}"
    else -> "${exception.javaClass.simpleName}: ${exception.message?.take(90) ?: ""}".trim()
}

/**
 * Self-test: acts as a minimal proxy — opens the same routes the proxy would,
 * sends a relay init plus a real `req_pq_multi`, and reports what Telegram
 * answered. This is what tells «маршрут открылся, но данных нет» apart from
 * «маршрут вообще недоступен».
 */
internal object RouteProbe {
    private val random = SecureRandom()

    fun launch(
        secret: ByteArray,
        workerDomains: List<String> = emptyList(),
        log: (String) -> Unit,
        onDone: () -> Unit = {},
        customIps: List<CustomIp> = emptyList(),
    ) {
        Thread({
            try {
                run(secret, workerDomains, log, customIps)
            } catch (exception: Exception) {
                log("Проверка прервана: ${shortError(exception)}")
            } finally {
                onDone()
            }
        }, "route-probe").apply { isDaemon = true }.start()
    }

    fun run(
        secret: ByteArray,
        workerDomains: List<String>,
        log: (String) -> Unit,
        customIps: List<CustomIp> = emptyList(),
    ) {
        log("Проверка канала до Telegram (DC2)…")
        val executor = Executors.newCachedThreadPool()
        var socket: RawWebSocket? = null
        try {
            val router = TransportRouter(log, executor, workerDomains, customIps = customIps)
            socket = router.probe(2, media = false)
            if (socket == null) {
                log("Итог проверки: ни один WebSocket-маршрут не открылся")
                log("Это сетевая блокировка: нужен VPN или свой сервер")
                return
            }

            val session = buildSession(secret)
            socket.setReadTimeout(10_000)
            socket.send(session.relayInit)
            socket.send(session.request)

            val started = System.nanoTime()
            val frame = try {
                socket.receive()
            } catch (_: SocketTimeoutException) {
                null
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000

            if (frame == null) {
                val closed = socket.closeInfo
                if (closed != null) {
                    log("Telegram закрыл канал ($closed) через $elapsed мс")
                    log("Итог проверки: канал открывается, но данных не пропускает")
                } else {
                    log("Ответа от Telegram нет за $elapsed мс")
                    log("Итог проверки: канал открывается, но данных не пропускает")
                }
                return
            }

            val plain = session.crypto.telegramToClient(frame)
            if (plain.size < 8) {
                log("Ответ неожиданного размера: ${plain.size} Б")
                log("Итог проверки: канал отвечает, но формат непонятен")
                return
            }
            val length = readIntLe(plain, 0)
            val ctor = readIntLe(plain, 4)
            val name = when (ctor) {
                RES_PQ -> "resPQ"
                else -> "0x%08X".format(ctor)
            }
            log("Ответ Telegram за $elapsed мс: $name (${length + 4} Б)")
            if (ctor == RES_PQ) log("Итог проверки: канал работает")
            else log("Итог проверки: ответ есть, но не resPQ")
        } finally {
            runCatching { socket?.close() }
            executor.shutdownNow()
        }
    }

    private class Session(
        val relayInit: ByteArray,
        val crypto: CryptoContext,
        val request: ByteArray,
    )

    private fun buildSession(secret: ByteArray): Session {
        // A client obfuscated2 init, exactly as a phone would produce it.
        val init = ByteArray(Obf.HANDSHAKE_LEN)
        while (true) {
            random.nextBytes(init)
            init[0] = 0x7E
            if (init[4] == 0.toByte() && init[5] == 0.toByte() &&
                init[6] == 0.toByte() && init[7] == 0.toByte()
            ) continue
            break
        }
        val keystream = AesCtr(
            Crypto.sha256(init.copyOfRange(Obf.SKIP_LEN, Obf.SKIP_LEN + Obf.PREKEY_LEN), secret),
            init.copyOfRange(Obf.SKIP_LEN + Obf.PREKEY_LEN, Obf.SKIP_LEN + Obf.PREKEY_LEN + Obf.IV_LEN),
        ).process(init)

        val tail = ByteArray(8)
        Obf.TAG_INTERMEDIATE.copyInto(tail, 0)
        tail[4] = 2
        tail[5] = 0
        tail[6] = random.nextInt(256).toByte()
        tail[7] = random.nextInt(256).toByte()
        for (offset in 0 until 8) {
            val position = Obf.TAG_POS + offset
            val streamByte = (keystream[position].toInt() xor init[position].toInt()) and 0xFF
            init[position] = (tail[offset].toInt() xor streamByte).toByte()
        }

        val handshake = ClientHandshake.parse(init, secret)
            ?: throw IllegalStateException("самопроверка рукопожатия не удалась")
        val relayInit = RelayInit.generate(handshake.protoTag, 2)
        val crypto = CryptoContext(handshake, secret, relayInit)

        val body = ByteArray(20)
        body[0] = 0xF1.toByte() // req_pq_multi = 0xbe7e8ef1
        body[1] = 0x8E.toByte()
        body[2] = 0x7E.toByte()
        body[3] = 0xBE.toByte()
        val nonce = ByteArray(16)
        random.nextBytes(nonce)
        nonce.copyInto(body, 4)

        val plain = ByteArray(4 + body.size)
        plain[0] = body.size.toByte()
        body.copyInto(plain, 4)
        return Session(relayInit, crypto, crypto.clientToTelegram(plain).second)
    }

    private fun readIntLe(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    private const val RES_PQ = 0x05162463
}
