package org.tgwsproxy.mobile.core

/**
 * Re-frames the MTProto stream into whole packets, because WebSocket frames
 * must carry exactly one MTProto message each.
 *
 * Lengths are read from the *plaintext* stream (decrypted client traffic);
 * the encrypted stream is cut at the same offsets. Both buffers are appended
 * in lockstep so offsets always line up.
 */
internal class MsgSplitter(private val proto: Int) {

    private var cipherBuffer = ByteArray(0)
    private var plainBuffer = ByteArray(0)
    private var disabled = false

    fun split(encrypted: ByteArray, plain: ByteArray): List<ByteArray> {
        if (encrypted.isEmpty()) return emptyList()
        if (disabled) return listOf(encrypted)

        cipherBuffer += encrypted
        plainBuffer += plain

        val parts = ArrayList<ByteArray>(4)
        var offset = 0
        while (offset < cipherBuffer.size) {
            val packetLength = nextPacketLength(offset, cipherBuffer.size - offset) ?: break
            if (packetLength <= 0) {
                parts.add(cipherBuffer.copyOfRange(offset, cipherBuffer.size))
                offset = cipherBuffer.size
                disabled = true
                break
            }
            parts.add(cipherBuffer.copyOfRange(offset, offset + packetLength))
            offset += packetLength
        }

        if (offset > 0) {
            cipherBuffer = cipherBuffer.copyOfRange(offset, cipherBuffer.size)
            plainBuffer = plainBuffer.copyOfRange(offset, plainBuffer.size)
        }
        return parts
    }

    /** Whatever is left when the client disconnects; sent as-is. */
    fun flush(): List<ByteArray> {
        if (cipherBuffer.isEmpty()) return emptyList()
        val tail = cipherBuffer
        cipherBuffer = ByteArray(0)
        plainBuffer = ByteArray(0)
        return listOf(tail)
    }

    /** null = need more bytes; negative = stream is not MTProto, stop splitting. */
    private fun nextPacketLength(offset: Int, available: Int): Int? {
        if (available <= 0) return null
        return when (proto) {
            Obf.PROTO_ABRIDGED -> abridgedLength(offset, available)
            Obf.PROTO_INTERMEDIATE, Obf.PROTO_PADDED -> intermediateLength(offset, available)
            else -> PACKET_INVALID
        }
    }

    private fun abridgedLength(offset: Int, available: Int): Int? {
        val first = plainBuffer[offset].toInt() and 0xFF
        val payloadLength: Int
        val headerLength: Int
        if (first == 0x7F || first == 0xFF) {
            if (available < 4) return null
            payloadLength = ((plainBuffer[offset + 1].toInt() and 0xFF) or
                ((plainBuffer[offset + 2].toInt() and 0xFF) shl 8) or
                ((plainBuffer[offset + 3].toInt() and 0xFF) shl 16)) * 4
            headerLength = 4
        } else {
            payloadLength = (first and 0x7F) * 4
            headerLength = 1
        }
        if (payloadLength <= 0) return PACKET_INVALID
        val packetLength = headerLength + payloadLength
        if (available < packetLength) return null
        return packetLength
    }

    private fun intermediateLength(offset: Int, available: Int): Int? {
        if (available < 4) return null
        var payloadLength = 0
        for (index in 0 until 4) {
            payloadLength = payloadLength or
                ((plainBuffer[offset + index].toInt() and 0xFF) shl (8 * index))
        }
        payloadLength = payloadLength and 0x7FFFFFFF
        if (payloadLength <= 0) return PACKET_INVALID
        val packetLength = 4 + payloadLength
        if (available < packetLength) return null
        return packetLength
    }

    private companion object {
        const val PACKET_INVALID = -1
    }
}
