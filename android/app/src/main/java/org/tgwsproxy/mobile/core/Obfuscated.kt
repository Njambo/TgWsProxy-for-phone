package org.tgwsproxy.mobile.core

import java.security.SecureRandom

internal object Obf {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val KEY_LEN = 32
    const val IV_LEN = 16
    const val TAG_POS = 56
    const val DC_POS = 60

    val TAG_ABRIDGED = byteArrayOf(0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte())
    val TAG_INTERMEDIATE = byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte())
    val TAG_PADDED = byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte())

    val PROTO_ABRIDGED = 0xEFEFEFEF.toInt()
    val PROTO_INTERMEDIATE = 0xEEEEEEEE.toInt()
    val PROTO_PADDED = 0xDDDDDDDD.toInt()

    fun tagName(tag: ByteArray): String = when {
        tag.contentEquals(TAG_ABRIDGED) -> "abridged"
        tag.contentEquals(TAG_INTERMEDIATE) -> "intermediate"
        else -> "padded"
    }

    fun protoInt(tag: ByteArray): Int = when {
        tag.contentEquals(TAG_ABRIDGED) -> PROTO_ABRIDGED
        tag.contentEquals(TAG_INTERMEDIATE) -> PROTO_INTERMEDIATE
        else -> PROTO_PADDED
    }
}

/** Parsed 64-byte obfuscated2 client handshake. */
internal class ClientHandshake(
    val dc: Int,
    val isMedia: Boolean,
    val protoTag: ByteArray,
    val prekeyIv: ByteArray,
) {
    val protoInt: Int get() = Obf.protoInt(protoTag)
    val protoName: String get() = Obf.tagName(protoTag)

    companion object {
        /**
         * Returns null when the secret does not match (the decrypted proto tag
         * is then garbage) or the handshake is malformed.
         */
        fun parse(init: ByteArray, secret: ByteArray): ClientHandshake? {
            if (init.size < Obf.HANDSHAKE_LEN) return null
            val prekeyIv = init.copyOfRange(
                Obf.SKIP_LEN,
                Obf.SKIP_LEN + Obf.PREKEY_LEN + Obf.IV_LEN
            )
            val key = Crypto.sha256(prekeyIv.copyOfRange(0, Obf.PREKEY_LEN), secret)
            val iv = prekeyIv.copyOfRange(Obf.PREKEY_LEN, Obf.PREKEY_LEN + Obf.IV_LEN)
            val decrypted = AesCtr(key, iv).process(init)
            if (decrypted.size < Obf.HANDSHAKE_LEN) return null

            val tag = decrypted.copyOfRange(Obf.TAG_POS, Obf.TAG_POS + 4)
            val known = tag.contentEquals(Obf.TAG_ABRIDGED) ||
                tag.contentEquals(Obf.TAG_INTERMEDIATE) ||
                tag.contentEquals(Obf.TAG_PADDED)
            if (!known) return null

            val raw = (decrypted[Obf.DC_POS].toInt() and 0xFF) or
                ((decrypted[Obf.DC_POS + 1].toInt() and 0xFF) shl 8)
            val dcIndex = raw.toShort().toInt()
            val dc = if (dcIndex < 0) -dcIndex else dcIndex
            if (dc == 0) return null

            return ClientHandshake(dc, dcIndex < 0, tag, prekeyIv)
        }
    }
}

/**
 * Builds the 64-byte init the proxy sends upstream in place of the client's.
 * The relay only sees this fake handshake; the client's real encryption is
 * re-applied per message on top of the relay keystream.
 */
internal object RelayInit {
    private val random = SecureRandom()

    private val reservedStarts = arrayOf(
        byteArrayOf(0x48, 0x45, 0x41, 0x44), // "HEAD"
        byteArrayOf(0x50, 0x4F, 0x53, 0x54), // "POST"
        byteArrayOf(0x47, 0x45, 0x54, 0x20), // "GET "
        Obf.TAG_INTERMEDIATE,
        Obf.TAG_PADDED,
        byteArrayOf(0x16, 0x03, 0x01, 0x02), // TLS ClientHello
        byteArrayOf(0x4F, 0x50, 0x54, 0x49), // "OPTI"
    )

    fun generate(protoTag: ByteArray, dcIndex: Int): ByteArray {
        val init = ByteArray(Obf.HANDSHAKE_LEN)
        while (true) {
            random.nextBytes(init)
            val first = init[0].toInt() and 0xFF
            if (first == 0xEF) continue
            val start = init.copyOfRange(0, 4)
            if (reservedStarts.any { start.contentEquals(it) }) continue
            if (init[4] == 0.toByte() && init[5] == 0.toByte() &&
                init[6] == 0.toByte() && init[7] == 0.toByte()
            ) continue
            break
        }

        val encrypted = AesCtr(
            init.copyOfRange(Obf.SKIP_LEN, Obf.SKIP_LEN + Obf.PREKEY_LEN),
            init.copyOfRange(
                Obf.SKIP_LEN + Obf.PREKEY_LEN,
                Obf.SKIP_LEN + Obf.PREKEY_LEN + Obf.IV_LEN
            )
        ).process(init)

        val tail = ByteArray(8)
        System.arraycopy(protoTag, 0, tail, 0, 4)
        tail[4] = (dcIndex and 0xFF).toByte()
        tail[5] = ((dcIndex shr 8) and 0xFF).toByte()
        tail[6] = random.nextInt(256).toByte()
        tail[7] = random.nextInt(256).toByte()

        val result = init.copyOf()
        for (offset in 0 until 8) {
            val keystreamByte = (encrypted[Obf.TAG_POS + offset].toInt() xor
                init[Obf.TAG_POS + offset].toInt()) and 0xFF
            result[Obf.TAG_POS + offset] = (tail[offset].toInt() xor keystreamByte).toByte()
        }
        return result
    }
}
