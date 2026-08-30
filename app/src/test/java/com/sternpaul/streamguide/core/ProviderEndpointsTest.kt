package com.sternpaul.streamguide.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderEndpointsTest {
    @Test fun buildsEncodedXtreamPlaylistAndEpgUrls() {
        val provider = ProviderConfig(ProviderType.XTREAM, "TV", serverUrl = "https://iptv.example/", username = "paul+tv", password = "p a&ss")
        assertEquals("https://iptv.example/get.php?username=paul%2Btv&password=p+a%26ss&type=m3u_plus&output=ts", ProviderEndpoints.playlist(provider))
        assertEquals("https://iptv.example/xmltv.php?username=paul%2Btv&password=p+a%26ss", ProviderEndpoints.epg(provider))
    }

    @Test fun explicitEpgUrlWins() {
        val provider = ProviderConfig(ProviderType.XTREAM, "TV", serverUrl = "https://x", username = "u", password = "p", epgUrl = "https://guide/epg.xml.gz")
        assertEquals("https://guide/epg.xml.gz", ProviderEndpoints.epg(provider))
    }
}
