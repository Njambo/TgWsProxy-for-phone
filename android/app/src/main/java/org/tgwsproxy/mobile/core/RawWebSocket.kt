package org.tgwsproxy.mobile.core

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
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

    fun send(payload: ByteArray) = sendFrame(OPCODE_BINARY, payload)

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

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4)
        RANDOM.nextBytes(mask)
        val masked = ByteArray(payload.size) { index ->
            (payload[index].toInt() xor mask[index and 3].toInt()).toByte()
        }

        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        when {
            masked.size < 126 -> header.write(0x80 or masked.size)
            masked.size < 65536 -> {
                header.write(0x80 or 126)
                header.write((masked.size ushr 8) and 0xFF)
                header.write(masked.size and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                val value = masked.size.toLong()
                for (shift in 56 downTo 0 step 8) {
                    header.write(((value ushr shift) and 0xFF).toInt())
                }
            }
        }
        header.write(mask)
        header.write(masked)

        synchronized(writeLock) {
            output.write(header.toByteArray())
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
        private const val MAX_MESSAGE = 16 * 1024 * 1024
        private const val OPCODE_CONTINUATION = 0x0
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_BINARY = 0x2
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA

        fun connect(
            host: String,
            path: String = "/apiws",
            connectTimeoutMs: Int = 10_000,
        ): RawWebSocket {
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress(host, 443), connectTimeoutMs)
                plain.soTimeout = connectTimeoutMs
                plain.tcpNoDelay = true

                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val tls = factory.createSocket(plain, host, 443, true) as SSLSocket
                tls.sslParameters = tls.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                tls.startHandshake()
                tls.soTimeout = 0

                val output = BufferedOutputStream(tls.outputStream)
                val input = BufferedInputStream(tls.inputStream)

                val keyBytes = ByteArray(16)
                RANDOM.nextBytes(keyBytes)
                val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)

                val request = "GET $path HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Protocol: binary\r\n" +
                    "\r\n"
                output.write(request.toByteArray(Charsets.US_ASCII))
                output.flush()

                val status = readLine(input) ?: throw IOException("empty WebSocket response")
                if (!status.contains(" 101")) {
                    throw IOException("WebSocket upgrade rejected: $status")
                }
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                }
                return RawWebSocket(tls, input, output)
            } catch (exception: Exception) {
                runCatching { plain.close() }
                throw exception
            }
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
