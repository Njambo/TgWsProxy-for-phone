package org.tgwsproxy.mobile.core

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal blocking WebSocket client (RFC 6455, binary frames only).
 *
 * Deliberately dependency-free: the proxy is a thread-per-connection
 * forwarding loop, so a blocking socket is a better fit than an async stack.
 */
internal class RawWebSocket private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) {
    private val writeLock = Any()

    /** Set when the peer sent a close frame: "code=… reason=…". */
    @Volatile
    var closeInfo: String? = null
        private set

    /**
     * True when the far end is a plain byte pipe (the user's Cloudflare
     * Worker), where WS message boundaries carry no meaning. Then the stream
     * goes out as it arrives instead of one frame per MTProto packet.
     */
    @Volatile
    var byteStream: Boolean = false

    fun send(payload: ByteArray) = sendFrame(OPCODE_BINARY, payload)

    fun setReadTimeout(timeoutMs: Int) {
        runCatching { socket.soTimeout = timeoutMs }
    }

    /**
     * Blocks until a complete data message arrives.
     * Returns null on a clean close or EOF.
     */
    fun receive(): ByteArray? {
        val message = ByteArrayOutputStream()
        while (true) {
            val first = input.read()
            if (first < 0) return null
            val second = input.read()
            if (second < 0) return null

            val fin = (first and 0x80) != 0
            val opcode = first and 0x0F
            var length = (second and 0x7F).toLong()
            if (length == 126L) {
                length = ((readByte() shl 8) or readByte()).toLong()
            } else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or readByte().toLong() }
            }
            if (length < 0 || length > MAX_FRAME) throw IOException("WS frame too large: $length")

            val masked = (second and 0x80) != 0
            val mask = if (masked) readFully(4) else null
            val payload = readFully(length.toInt())
            if (mask != null) {
                for (index in payload.indices) {
                    payload[index] =
                        (payload[index].toInt() xor mask[index and 3].toInt()).toByte()
                }
            }

            when (opcode) {
                OPCODE_CLOSE -> {
                    closeInfo = describeClose(payload)
                    runCatching {
                        sendFrame(OPCODE_CLOSE, payload.copyOfRange(0, minOf(payload.size, 2)))
                    }
                    close()
                    return null
                }
                OPCODE_PING -> runCatching { sendFrame(OPCODE_PONG, payload) }
                OPCODE_PONG -> Unit
                OPCODE_CONTINUATION, OPCODE_BINARY, OPCODE_TEXT -> {
                    message.write(payload)
                    if (message.size() > MAX_MESSAGE) throw IOException("WS message too large")
                    if (fin) return message.toByteArray()
                }
                else -> Unit
            }
        }
    }

    fun close() {
        runCatching { socket.close() }
        runCatching { input.close() }
        runCatching { output.close() }
    }

    /**
     * Writes one masked binary frame. The payload is masked in place-sized
     * chunks straight into the output stream: media traffic would otherwise
     * allocate and copy the whole payload (up to a megabyte per packet) twice.
     */
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4)
        RANDOM.nextBytes(mask)

        val header = when {
            payload.size < 126 -> byteArrayOf(
                (0x80 or opcode).toByte(),
                (0x80 or payload.size).toByte(),
                mask[0], mask[1], mask[2], mask[3],
            )
            payload.size < 65536 -> byteArrayOf(
                (0x80 or opcode).toByte(),
                (0x80 or 126).toByte(),
                (payload.size ushr 8).toByte(),
                payload.size.toByte(),
                mask[0], mask[1], mask[2], mask[3],
            )
            else -> ByteArray(14).also { bytes ->
                bytes[0] = (0x80 or opcode).toByte()
                bytes[1] = (0x80 or 127).toByte()
                val size = payload.size.toLong()
                for (index in 0 until 8) {
                    bytes[2 + index] = ((size ushr (56 - 8 * index)) and 0xFF).toByte()
                }
                mask.copyInto(bytes, 10)
            }
        }

        synchronized(writeLock) {
            output.write(header)
            val scratch = ByteArray(minOf(payload.size, MASK_CHUNK))
            var offset = 0
            while (offset < payload.size) {
                val count = minOf(scratch.size, payload.size - offset)
                for (index in 0 until count) {
                    scratch[index] = (payload[offset + index].toInt() xor
                        mask[(offset + index) and 3].toInt()).toByte()
                }
                output.write(scratch, 0, count)
                offset += count
            }
            output.flush()
        }
    }

    private fun readByte(): Int {
        val value = input.read()
        if (value < 0) throw IOException("unexpected EOF in WS frame")
        return value
    }

    private fun readFully(size: Int): ByteArray {
        val data = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(data, offset, size - offset)
            if (count < 0) throw IOException("unexpected EOF in WS frame")
            offset += count
        }
        return data
    }

    companion object {
        private val RANDOM = SecureRandom()
        private const val MAX_FRAME = 4 * 1024 * 1024
        private const val MASK_CHUNK = 64 * 1024
        private const val MAX_MESSAGE = 16 * 1024 * 1024
        private const val OPCODE_CONTINUATION = 0x0
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_BINARY = 0x2
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA

        /**
         * @param host address to dial (may be a bare IP for the pinned gateway)
         * @param sni server name for the TLS handshake and certificate check
         * @param hostHeader value of the HTTP Host header
         * @param verifyHostname false for SNI fronting, where the presented
         *   certificate belongs to the fronted domain, not the SNI one
         */
        fun connect(
            host: String,
            sni: String = host,
            hostHeader: String = host,
            path: String = "/apiws",
            connectTimeoutMs: Int = 10_000,
            verifyHostname: Boolean = true,
            port: Int = 443,
            secure: Boolean = true,
        ): RawWebSocket {
            val plain = Socket()
            try {
                try {
                    plain.connect(InetSocketAddress(host, port), connectTimeoutMs)
                } catch (e: SocketTimeoutException) {
                    throw StageError("TCP", "таймаут ${connectTimeoutMs / 1000} с")
                } catch (e: ConnectException) {
                    throw StageError("TCP", "соединение отклонено")
                } catch (e: UnknownHostException) {
                    throw StageError("DNS", "имя не резолвится")
                }
                plain.soTimeout = connectTimeoutMs
                plain.tcpNoDelay = true

                val channel: Socket = if (secure) {
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val tls = factory.createSocket(plain, sni, port, true) as SSLSocket
                    tls.sslParameters = tls.sslParameters.apply {
                        endpointIdentificationAlgorithm = if (verifyHostname) "HTTPS" else null
                    }
                    try {
                        tls.startHandshake()
                    } catch (e: SocketTimeoutException) {
                        throw StageError("TLS", "таймаут рукопожатия")
                    } catch (e: SSLException) {
                        throw StageError("TLS", e.message?.take(80) ?: "рукопожатие отклонено")
                    } catch (e: IOException) {
                        throw StageError("TLS", e.message?.take(80) ?: "соединение сброшено")
                    }
                    tls
                } else {
                    plain
                }

                val output = BufferedOutputStream(channel.getOutputStream())
                val input = BufferedInputStream(channel.getInputStream())

                val keyBytes = ByteArray(16)
                RANDOM.nextBytes(keyBytes)
                val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)

                val request = "GET $path HTTP/1.1\r\n" +
                    "Host: $hostHeader\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Protocol: binary\r\n" +
                    "\r\n"
                output.write(request.toByteArray(Charsets.US_ASCII))
                output.flush()

                val status = readLine(input)
                    ?: throw StageError("WS", "соединение закрыто без ответа")
                if (!status.contains(" 101")) {
                    throw StageError("WS", status.substringAfter(' ', status).take(50))
                }
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                }
                channel.soTimeout = 0
                return RawWebSocket(channel, input, output)
            } catch (exception: Exception) {
                runCatching { plain.close() }
                throw exception
            }
        }

        private fun describeClose(payload: ByteArray): String {
            if (payload.size < 2) return "без кода"
            val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            val reason = if (payload.size > 2) {
                String(payload.copyOfRange(2, payload.size), Charsets.UTF_8)
                    .map { if (it.code in 32..126) it else ' ' }
                    .joinToString("")
                    .trim()
            } else {
                ""
            }
            return if (reason.isEmpty()) "code=$code"
            else "code=$code reason=\"$reason\""
        }

        private fun readLine(input: InputStream): String? {
            val buffer = ByteArrayOutputStream(128)
            while (true) {
                val value = input.read()
                if (value < 0) {
                    return if (buffer.size() == 0) null else String(buffer.toByteArray(), Charsets.US_ASCII)
                }
                if (value == '\n'.code) break
                if (value != '\r'.code) buffer.write(value)
            }
            return String(buffer.toByteArray(), Charsets.US_ASCII)
        }
    }
}
