package com.sternpaul.streamguide.core

import org.junit.Assert.*
import org.junit.Test

class PinHasherTest {
    @Test fun verifiesCorrectPinAndRejectsWrongPin() {
        val salt = byteArrayOf(1, 2, 3, 4)
        val hash = PinHasher.hash("2468", salt)
        assertTrue(PinHasher.verify("2468", salt, hash))
        assertFalse(PinHasher.verify("0000", salt, hash))
    }
}
