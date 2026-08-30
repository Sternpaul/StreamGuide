package com.sternpaul.streamguide.core

import java.security.MessageDigest

object PinHasher {
    fun hash(pin: String, salt: ByteArray): ByteArray {
        require(pin.length in 4..12 && pin.all(Char::isDigit)) { "PIN must contain 4–12 digits" }
        return MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray(Charsets.UTF_8))
    }

    fun verify(pin: String, salt: ByteArray, expected: ByteArray): Boolean = runCatching {
        MessageDigest.isEqual(hash(pin, salt), expected)
    }.getOrDefault(false)
}
