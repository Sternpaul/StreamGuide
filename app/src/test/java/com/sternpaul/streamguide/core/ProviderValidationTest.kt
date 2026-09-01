package com.sternpaul.streamguide.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderValidationTest {
    @Test fun acceptsHttpAndHttpsXtreamServers() {
        assertTrue(ProviderValidation.isXtreamValid("http://provider.example:8080", "user", "pass"))
        assertTrue(ProviderValidation.isXtreamValid("https://provider.example", "user", "pass"))
    }

    @Test fun rejectsUnsupportedSchemesAndMissingCredentials() {
        assertFalse(ProviderValidation.isXtreamValid("ftp://provider.example", "user", "pass"))
        assertFalse(ProviderValidation.isXtreamValid("http://provider.example", "", "pass"))
    }

    @Test fun acceptsRemoteAndLocalM3uSources() {
        assertTrue(ProviderValidation.isM3uValid("http://provider.example/list.m3u"))
        assertTrue(ProviderValidation.isM3uValid("https://provider.example/list.m3u8"))
        assertTrue(ProviderValidation.isM3uValid("content://media/playlist"))
    }
}
