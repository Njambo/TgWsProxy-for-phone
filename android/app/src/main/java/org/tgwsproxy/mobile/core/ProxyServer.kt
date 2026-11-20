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
    private val logLine: (String) -> Unit,
) {
    private val pool = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null

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

            val webSocket = connectWebSocket(dc, handshake.isMedia)
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

        val downstream = Thread({
            try {
                while (true) {
                    val frame = webSocket.receive() ?: break
                    if (frame.isEmpty()) continue
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
                for (part in splitter.split(encrypted, plain)) webSocket.send(part)
            }
            for (part in splitter.flush()) webSocket.send(part)
        } catch (_: Exception) {
        } finally {
            webSocket.close()
            runCatching { client.close() }
        }
    }

    private fun connectWebSocket(dc: Int, media: Boolean): RawWebSocket? {
        for (host in webSocketHosts(dc, media)) {
            try {
                val socket = RawWebSocket.connect(host)
                logLine("WebSocket $host")
                return socket
            } catch (exception: Exception) {
                logLine("WS $host: ${exception.message}")
            }
        }
        return null
    }

    private fun webSocketHosts(dc: Int, media: Boolean): List<String> {
        val index = if (dc == 203) 2 else dc
        return if (media) {
            listOf("kws$index-1.web.telegram.org", "kws$index.web.telegram.org")
        } else {
            listOf("kws$index.web.telegram.org")
        }
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
        val address = DC_IPS[dc]
        if (address == null) {
            logLine("DC$dc: нет адреса для TCP-фолбэка")
            return
        }

        val remote = Socket()
        try {
            remote.connect(InetSocketAddress(address, 443), 10_000)
            remote.tcpNoDelay = true
            remote.soTimeout = 0
            remote.getOutputStream().apply {
                write(relayInit)
                flush()
            }
            logLine("TCP fallback → $address:443")
        } catch (exception: Exception) {
            logLine("TCP fallback: ${exception.message}")
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

    private companion object {
        val DC_IPS: Map<Int, String> = mapOf(
            1 to "149.154.175.50",
            2 to "149.154.167.51",
            3 to "149.154.175.100",
            4 to "149.154.167.91",
            5 to "149.154.171.5",
            203 to "91.105.192.100",
        )
    }
}
