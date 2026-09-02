package com.sternpaul.streamguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticMessageSanitizerTest {
    @Test
    fun removesProviderCredentialsFromQueryStringsAndUserInfo() {
        val result = DiagnosticMessageSanitizer.sanitize(
            "Failed https://alice:secret@example.test/xmltv.php?username=alice&password=secret&token=abc"
        )

        assertFalse(result.contains("alice"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("abc"))
        assertEquals(
            "Failed https://•••:•••@example.test/xmltv.php?username=•••&password=•••&token=•••",
            result
        )
    }

    @Test
    fun makesMessagesSingleLineAndBoundsTheirLength() {
        val result = DiagnosticMessageSanitizer.sanitize("EPG failed\n" + "x".repeat(400))

        assertFalse(result.contains('\n'))
        assertEquals(300, result.length)
    }
}
