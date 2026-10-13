package com.sternpaul.streamguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamChannelsTest {
    private val account = XtreamAccount("http://tv.example:8080", "user", "pass")
    private val categories = mapOf("1" to "News", "2" to "Sport")

    @Test
    fun savedChannelsKeepTheirIdAndUrlSoFavouritesSurvive() {
        val previous = listOf(
            Channel("ard", "Das Erste", "http://cdn.example/user/pass/10", tvgId = "ard", favorite = true),
            Channel("9f3ab1c2d4e5f6a7b8c9", "No Guide", "http://cdn.example/user/pass/11", favorite = true),
            Channel("ard#aa11", "Das Erste FHD", "http://cdn.example/user/pass/12", tvgId = "ard")
        )
        val streams = listOf(
            XtreamStream("12", "Das Erste FHD", "1", "ard"),
            XtreamStream("10", "Das Erste HD", "1", "ard"),
            XtreamStream("11", "No Guide", "2"),
            XtreamStream("13", "Brand New", "9", "ard")
        )
        val built = XtreamChannels.build(account, streams, categories, previous)

        assertEquals(listOf("ard#aa11", "ard", "9f3ab1c2d4e5f6a7b8c9", "ard#xt-13"), built.map { it.id })
        assertEquals("http://cdn.example/user/pass/11", built[2].url)
        assertEquals("http://cdn.example/user/pass/13", built[3].url)
        assertEquals(listOf("News", "News", "Sport", "Other"), built.map { it.group })
        val reconciled = ChannelReconciler.reconcile(previous, built)
        assertEquals(listOf(false, true, true, false), reconciled.map { it.favorite })
    }

    @Test
    fun firstImportUsesTheStandardLiveUrlAndSkipsRepeatedStreams() {
        val built = XtreamChannels.build(account, listOf(XtreamStream("5", " One ", "1", "one"), XtreamStream("5", "One again", "2", "one"), XtreamStream("6", "Two", "1", "one"), XtreamStream("7", "", "1")), categories, emptyList())
        assertEquals(listOf("one", "one#xt-6"), built.map { it.id })
        assertEquals("http://tv.example:8080/live/user/pass/5.ts", built[0].url)
        assertEquals("One", built[0].name)
    }

    @Test
    fun repeatedImportsAreStable() {
        val streams = listOf(XtreamStream("5", "One", "1"), XtreamStream("6", "Two", "1", "two"))
        val first = XtreamChannels.build(account, streams, categories, emptyList())
        assertEquals(first, XtreamChannels.build(account, streams, categories, first))
        assertEquals(first.map { it.id to it.url }.toSet(), XtreamChannels.build(account, streams.reversed(), categories, first).map { it.id to it.url }.toSet())
    }

    @Test
    fun playlistFallbackKeepsIdsOfChannelsImportedThroughTheApi() {
        val previous = listOf(Channel("xt-5", "One", "http://tv.example:8080/live/user/pass/5.ts"), Channel("two", "Two", "http://tv.example:8080/live/user/pass/6.ts", tvgId = "two"))
        val parsed = listOf(Channel("hash-of-url", "One", "http://tv.example:8080/user/pass/5"), Channel("two", "Two", "http://tv.example:8080/user/pass/6", tvgId = "two"), Channel("other", "Three", "http://tv.example:8080/user/pass/7"))
        assertEquals(listOf("xt-5", "two", "other"), XtreamChannels.carryIds(previous, parsed).map { it.id })
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
