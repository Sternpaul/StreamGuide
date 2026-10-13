package com.sternpaul.streamguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamChannelsTest {
    private val account = XtreamAccount("http://tv.example:8080", "user", "pass")
    private val categories = mapOf("1" to "News", "2" to "Sport")

    @Test
    fun firstImportUsesTheStandardLiveUrlAndCountsWhatItSkips() {
        val loaded = XtreamChannels.load(account, listOf(XtreamStream("5", " One ", "1", "one"), XtreamStream("5", "One again", "2", "one"), XtreamStream("6", "Two", "9"), XtreamStream("7", "", "1"), XtreamStream("x", "Bad", "1")), categories, emptyList())
        assertEquals(listOf("One", "Two"), loaded.channels.map { it.name })
        assertEquals("http://tv.example:8080/live/user/pass/5.ts", loaded.channels[0].url)
        assertEquals(listOf("News", "Other"), loaded.channels.map { it.group })
        assertEquals(1, loaded.duplicates)
        assertEquals(2, loaded.invalid)
    }

    @Test
    fun newChannelsFollowTheUrlShapeOfSavedOnes() {
        val saved = listOf(Channel("a", "A", "http://cdn.example/user/pass/10"), Channel("b", "B", "http://cdn.example/user/pass/11"))
        assertEquals("http://cdn.example/user/pass/13", XtreamChannels.load(account, listOf(XtreamStream("13", "New")), categories, saved).channels.single().url)
    }

    @Test
    fun savedAddressesAreDroppedAfterALoginOrServerChange() {
        assertTrue(XtreamChannels.savedUrlStillValid(account, "http://tv.example:8080", "http://tv.example:8080/live/user/pass/5.ts"))
        assertTrue(XtreamChannels.savedUrlStillValid(account, null, "http://cdn.example/user/pass/5"))
        assertFalse(XtreamChannels.savedUrlStillValid(account, null, "http://tv.example:8080/live/user/oldpass/5.ts"))
        assertFalse(XtreamChannels.savedUrlStillValid(account, "http://old.example", "http://old.example/live/user/pass/5.ts"))
        val saved = listOf(Channel("a", "A", "http://old.example/live/user/pass/10.m3u8"))
        assertEquals("http://tv.example:8080/live/user/pass/13.ts", XtreamChannels.load(account, listOf(XtreamStream("13", "New")), categories, saved, "http://old.example").channels.single().url)
    }

    @Test
    fun recognisesXtreamLoginsAndGetPhpLinks() {
        assertEquals(account, XtreamChannels.account(ProviderConfig(ProviderType.XTREAM, "x", serverUrl = "http://tv.example:8080/", username = "user", password = "pass")))
        assertEquals(account, XtreamChannels.account(ProviderConfig(ProviderType.M3U, "x", playlistUrl = "http://tv.example:8080/get.php?username=user&password=pass&type=m3u_plus")))
        assertNull(XtreamChannels.account(ProviderConfig(ProviderType.M3U, "x", playlistUrl = "https://example.org/list.m3u8")))
        assertNull(XtreamChannels.account(ProviderConfig(ProviderType.M3U, "x", playlistUrl = "content://docs/1")))
    }

    @Test
    fun streamKeyOnlyAcceptsNumericStreamIds() {
        assertEquals("1234", XtreamChannels.streamKey("http://h/live/u/p/1234.ts"))
        assertEquals("1234", XtreamChannels.streamKey("http://h/u/p/1234?token=1"))
        assertNull(XtreamChannels.streamKey("https://h/hls/channel/index.m3u8"))
    }
}
