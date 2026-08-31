package com.sternpaul.streamguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderHeadersTest {
    @Test fun usesCustomUserAgentAndRefererWhenConfigured() {
        val provider = ProviderConfig(ProviderType.M3U, "TV", userAgent = "MyPlayer/1", referer = "https://portal.example/")
        val headers = ProviderHeaders.forProvider(provider)
        assertEquals("MyPlayer/1", headers["User-Agent"])
        assertEquals("https://portal.example/", headers["Referer"])
    }

    @Test fun suppliesSafeDefaultAndOmitsBlankReferer() {
        val headers = ProviderHeaders.forProvider(ProviderConfig(ProviderType.M3U, "TV"))
        assertEquals("StreamGuide/0.4 FireTV", headers["User-Agent"])
        assertFalse(headers.containsKey("Referer"))
    }
}
