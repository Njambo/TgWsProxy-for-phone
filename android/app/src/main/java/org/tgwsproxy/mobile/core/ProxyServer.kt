package org.tgwsproxy.mobile.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Local MTProto proxy: accepts obfuscated2 connections on 127.0.0.1, unwraps
 * them and tunnels the plaintext MTProto stream to Telegram over WebSocket
 * (with a raw-TCP fallback to the DC when no WS host answers).
 */
internal class ProxyServer(
    private val port: Int,
    private val secret: ByteArray,
    private val workerDomains: List<String> = emptyList(),
    private val onRoute: (String?) -> Unit = {},
    private val logLine: (String) -> Unit,
) {
    private val pool = Executors.newCachedThreadPool()
    private val router = TransportRouter(logLine, pool, workerDomains, onRoute)
    private var serverSocket: ServerSocket? = null

    /** TCP fallback state: a blocked DC must not be retried on every client. */
    @Volatile
    private var tcpBlockedUntil = 0L
    @Volatile
    private var tcpFailures = 0

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        serverSocket = socket
        running = true
        logLine("Прокси слушает 127.0.0.1:$port")
        Thread({ acceptLoop(socket) }, "mtproto-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool.shutdownNow()
        logLine("Прокси остановлен")
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        val peer = client.inetAddress?.hostAddress ?: "?"
        try {
            // Telegram asks for a media chunk and waits for the answer: a
            // Nagle delay on the local hop adds latency to every request.
            runCatching { client.tcpNoDelay = true }
            client.soTimeout = 15_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            val init = readExactly(input, Obf.HANDSHAKE_LEN)
            if (init == null) {
                client.close()
                return
            }
            val handshake = ClientHandshake.parse(init, secret)
            if (handshake == null) {
                logLine("$peer: неверный секрет или протокол")
                client.close()
                return
            }

            var dc = handshake.dc
            if (dc >= 10_000) dc -= 10_000
            if (dc <= 0) {
                client.close()
                return
            }
            client.soTimeout = 0

            val relayInit = RelayInit.generate(
                handshake.protoTag,
                if (handshake.isMedia) -dc else dc
            )
            val crypto = CryptoContext(handshake, secret, relayInit)
            val mediaLabel = if (handshake.isMedia) " media" else ""
            logLine("$peer → DC$dc$mediaLabel (${handshake.protoName})")

            val webSocket = router.open(dc, handshake.isMedia)
            if (webSocket != null) {
                bridgeWebSocket(
                    client, input, output, webSocket, relayInit, crypto,
                    MsgSplitter(handshake.protoInt)
                )
            } else {
                bridgeTcp(client, input, output, dc, relayInit, crypto)
            }
        } catch (exception: Exception) {
            logLine("$peer: ${exception.javaClass.simpleName}: ${exception.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    // ---- WebSocket transport ------------------------------------------------

    private fun bridgeWebSocket(
        client: Socket,
        input: InputStream,
        output: BufferedOutputStream,
        webSocket: RawWebSocket,
        relayInit: ByteArray,
        crypto: CryptoContext,
        splitter: MsgSplitter,
    ) {
        try {
            webSocket.send(relayInit)
        } catch (exception: Exception) {
            logLine("WS init: ${exception.message}")
            webSocket.close()
            return
        }

        val startedAt = System.currentTimeMillis()
        var upBytes = 0L
        var downBytes = 0L
        // A Worker pipes raw bytes, so packet boundaries mean nothing there:
        // sending the stream as it arrives saves a frame and a copy per packet.
        val rawStream = webSocket.byteStream

        val downstream = Thread({
            try {
                while (true) {
                    val frame = webSocket.receive()
                    if (frame == null) {
                        val info = webSocket.closeInfo
                        logLine(if (info != null) "WS закрыт Telegram: $info" else "WS: канал закрыт без кадра")
                        break
                    }
                    if (frame.isEmpty()) continue
                    downBytes += frame.size
                    val plain = crypto.telegramToClient(frame)
                    synchronized(output) {
                        output.write(plain)
                        output.flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                webSocket.close()
                runCatching { client.close() }
            }
        }, "proxy-ws-down")
        downstream.isDaemon = true
        downstream.start()

        try {
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                val (plain, encrypted) = crypto.clientToTelegram(buffer.copyOf(count))
                upBytes += encrypted.size
                if (rawStream) {
                    webSocket.send(encrypted)
                } else {
                    for (part in splitter.split(encrypted, plain)) webSocket.send(part)
                }
            }
            if (!rawStream) {
                for (part in splitter.flush()) webSocket.send(part)
            }
        } catch (_: Exception) {
        } finally {
            webSocket.close()
            runCatching { client.close() }
            val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
            logLine(
                "WS сессия: ↑${formatBytes(upBytes)} ↓${formatBytes(downBytes)}" +
                    " за ${"%.0f".format(seconds)} с"
            )
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f МБ".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.0f КБ".format(bytes / 1024.0)
        else -> "$bytes Б"
    }

    // ---- raw TCP fallback ---------------------------------------------------

    private fun bridgeTcp(
        client: Socket,
        input: InputStream,
        output: BufferedOutputStream,
        dc: Int,
        relayInit: ByteArray,
        crypto: CryptoContext,
    ) {
        val address = TelegramDc.tcp[dc]
        if (address == null) {
            logLine("DC$dc: нет адреса для TCP-фолбэка")
            return
        }

        val now = System.currentTimeMillis()
        if (now < tcpBlockedUntil) {
            logLine("TCP-фолбэк на паузе ещё ${(tcpBlockedUntil - now) / 1000 + 1} с")
            return
        }

        val remote = Socket()
        try {
            remote.connect(InetSocketAddress(address, 443), 6_000)
            remote.tcpNoDelay = true
            remote.soTimeout = 0
            remote.getOutputStream().apply {
                write(relayInit)
                flush()
            }
            logLine("TCP fallback → $address:443")
            tcpFailures = 0
        } catch (exception: Exception) {
            tcpFailures += 1
            val delay = minOf(5_000L shl minOf(tcpFailures - 1, 4), 60_000L)
            tcpBlockedUntil = System.currentTimeMillis() + delay
            logLine("TCP fallback: ${shortError(exception)} · пауза ${delay / 1000} с")
            runCatching { remote.close() }
            return
        }

        val remoteInput = BufferedInputStream(remote.getInputStream())
        val remoteOutput = BufferedOutputStream(remote.getOutputStream())

        val upstream = Thread({
            try {
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    remoteOutput.write(crypto.clientToTelegram(buffer.copyOf(count)).second)
                    synchronized(remoteOutput) { remoteOutput.flush() }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { remote.close() }
                runCatching { client.close() }
            }
        }, "proxy-tcp-up")

        val downstream = Thread({
            try {
                val buffer = ByteArray(65536)
                while (true) {
                    val count = remoteInput.read(buffer)
                    if (count < 0) break
                    synchronized(output) {
                        output.write(crypto.telegramToClient(buffer.copyOf(count)))
                        output.flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { remote.close() }
                runCatching { client.close() }
            }
        }, "proxy-tcp-down")

        upstream.isDaemon = true
        downstream.isDaemon = true
        upstream.start()
        downstream.start()
        upstream.join()
        downstream.join()
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray? {
        val data = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(data, offset, size - offset)
            if (count < 0) return null
            offset += count
        }
        return data
    }

}
