package org.tgwsproxy.mobile

import java.security.SecureRandom

private const val HEX = "0123456789abcdef"

fun randomSecretHex(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    val builder = StringBuilder(32)
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        builder.append(HEX[value ushr 4])
        builder.append(HEX[value and 0x0F])
    }
    return builder.toString()
}

fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex string must have even length" }
    val result = ByteArray(hex.length / 2)
    for (index in result.indices) {
        val high = Character.digit(hex[index * 2], 16)
        val low = Character.digit(hex[index * 2 + 1], 16)
        require(high >= 0 && low >= 0) { "invalid hex character" }
        result[index] = ((high shl 4) or low).toByte()
    }
    return result
}
