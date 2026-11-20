package org.tgwsproxy.mobile.core

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object Crypto {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) digest.update(part)
        return digest.digest()
    }
}

/**
 * AES-256-CTR stream. Encryption and decryption are the same operation,
 * so one instance serves as a keystream generator for a single direction.
 * Not thread-safe: one instance per direction; guard with a lock.
 */
internal class AesCtr(key: ByteArray, iv: ByteArray) {
    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    fun process(data: ByteArray): ByteArray =
        if (data.isEmpty()) ByteArray(0) else cipher.update(data) ?: ByteArray(0)

    fun skip(bytes: Int) {
        if (bytes > 0) cipher.update(ByteArray(bytes))
    }
}

/**
 * Four keystreams for one proxied client session.
 *
 * client -> tg: decrypt what Telegram sent to the phone, re-encrypt for the relay.
 * tg -> client: decrypt what the relay sent back, re-encrypt for the phone.
 */
internal class CryptoContext(
    client: ClientHandshake,
    secret: ByteArray,
    relayInit: ByteArray,
) {
    private val clientDecrypt = AesCtr(
        Crypto.sha256(client.prekeyIv.copyOfRange(0, Obf.PREKEY_LEN), secret),
        client.prekeyIv.copyOfRange(Obf.PREKEY_LEN, Obf.PREKEY_LEN + Obf.IV_LEN)
    ).apply { skip(Obf.HANDSHAKE_LEN) }

    private val reversedClient = client.prekeyIv.reversedArray()
    private val clientEncrypt = AesCtr(
        Crypto.sha256(reversedClient.copyOfRange(0, Obf.PREKEY_LEN), secret),
        reversedClient.copyOfRange(Obf.PREKEY_LEN, Obf.PREKEY_LEN + Obf.IV_LEN)
    )

    private val telegramEncrypt = AesCtr(
        relayInit.copyOfRange(Obf.SKIP_LEN, Obf.SKIP_LEN + Obf.PREKEY_LEN),
        relayInit.copyOfRange(Obf.SKIP_LEN + Obf.PREKEY_LEN, Obf.SKIP_LEN + Obf.PREKEY_LEN + Obf.IV_LEN)
    ).apply { skip(Obf.HANDSHAKE_LEN) }

    private val reversedRelay = relayInit.copyOfRange(
        Obf.SKIP_LEN,
        Obf.SKIP_LEN + Obf.PREKEY_LEN + Obf.IV_LEN
    ).reversedArray()

    private val telegramDecrypt = AesCtr(
        reversedRelay.copyOfRange(0, Obf.KEY_LEN),
        reversedRelay.copyOfRange(Obf.KEY_LEN, Obf.KEY_LEN + Obf.IV_LEN)
    )

    /** Returns (plaintext-in-Telegram-format, ciphertext-for-relay). */
    @Synchronized
    fun clientToTelegram(data: ByteArray): Pair<ByteArray, ByteArray> {
        val plain = clientDecrypt.process(data)
        return plain to telegramEncrypt.process(plain)
    }

    @Synchronized
    fun telegramToClient(data: ByteArray): ByteArray =
        clientEncrypt.process(telegramDecrypt.process(data))
}
