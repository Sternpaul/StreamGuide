package com.sternpaul.streamguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelImportTest {
    private val account = XtreamAccount("http://tv.example:8080", "user", "pass")
    private val source = PlaylistSource("xtream", "tv.example", "user")
    private val api = ChannelImport.Options(matchById = false, useStreamKeys = true) { XtreamChannels.savedUrlStillValid(account, null, it.url) }
    private val playlist = ChannelImport.Options(matchById = true, useStreamKeys = true)
    private fun live(id: Int) = "http://tv.example:8080/live/user/pass/$id.ts"
    private fun apiChannels(vararg entries: Triple<Int, String, String>) =
        XtreamChannels.load(account, entries.map { XtreamStream(it.first.toString(), it.second, "1", it.third) }, mapOf("1" to "News"), emptyList()).channels

    // Channels as the old playlist import saved them: tvg-id or URL hash as id, short URLs.
    private val saved = listOf(
        Channel("ard", "Das Erste HD", "http://tv.example:8080/user/pass/10", "News", tvgId = "ard", favorite = true, manualRank = 1000),
        Channel("9f3ab1c2d4", "No Guide", "http://tv.example:8080/user/pass/11", "News", hidden = true, manualRank = 2000),
        Channel("ard#aa11", "Das Erste FHD", "http://tv.example:8080/user/pass/12", "News", tvgId = "ard", customName = "ARD", manualRank = 3000)
    )

    @Test
    fun switchingToTheLiveApiKeepsEverySavedChannel() {
        val incoming = apiChannels(Triple(12, "Das Erste FHD", "ard"), Triple(10, "Das Erste HD", "ard"), Triple(11, "No Guide", ""), Triple(13, "Brand New", "ard"))
        val result = ChannelImport.merge(saved, source, emptyList(), source, incoming, api)

        assertEquals(listOf("ard#aa11", "ard", "9f3ab1c2d4", "ard#xt-13"), result.channels.map { it.id })
        assertEquals(listOf(false, true, false, false), result.channels.map { it.favorite })
        assertEquals("ARD", result.channels[0].customName)
        assertTrue(result.channels[2].hidden)
        assertEquals("http://tv.example:8080/user/pass/10", result.channels[1].url)
        assertEquals(listOf(3000L, 1000L, 2000L, 4000L), result.channels.map { it.manualRank })
        assertEquals(listOf(3, 1, 0, 0), listOf(result.kept, result.added, result.restored, result.missing))
    }

    @Test
    fun reloadingTwiceChangesNothing() {
        val incoming = apiChannels(Triple(10, "Das Erste HD", "ard"), Triple(11, "No Guide", ""), Triple(12, "Das Erste FHD", "ard"))
        val first = ChannelImport.merge(saved, source, emptyList(), source, incoming, api)
        val second = ChannelImport.merge(first.channels, source, first.archive, source, incoming, api)
        assertEquals(first.channels, second.channels)
        assertEquals(listOf(3, 0, 0, 0), listOf(second.kept, second.added, second.restored, second.missing))
    }

    @Test
    fun aChannelThatDisappearsKeepsItsStateUntilItReturns() {
        val without = apiChannels(Triple(11, "No Guide", ""), Triple(12, "Das Erste FHD", "ard"))
        val gone = ChannelImport.merge(saved, source, emptyList(), source, without, api)
        assertEquals(1, gone.missing)
        assertEquals(listOf("ard"), gone.archive.map { it.channel.id })
        assertFalse(gone.channels.any { it.id == "ard" })

        val back = ChannelImport.merge(gone.channels, source, gone.archive, source, apiChannels(Triple(11, "No Guide", ""), Triple(12, "Das Erste FHD", "ard"), Triple(10, "Das Erste HD", "ard")), api)
        val restored = back.channels.single { it.id == "ard" }
        assertTrue(restored.favorite)
        assertEquals(1000L, restored.manualRank)
        assertEquals(1, back.restored)
        assertTrue(back.archive.isEmpty())
    }

    @Test
    fun aNewChannelNeverTakesOverAnArchivedOne() {
        val archive = listOf(SavedChannel(saved[0], source.encode()))
        val result = ChannelImport.merge(emptyList(), source, archive, source, apiChannels(Triple(99, "Other Channel", "ard")), api)
        assertEquals("ard#xt-99", result.channels.single().id)
        assertFalse(result.channels.single().favorite)
        assertEquals(listOf("ard"), result.archive.map { it.channel.id })
    }

    @Test
    fun aDifferentPlaylistNeverInheritsStreamAddressesOrStateByNumber() {
        val otherAccount = XtreamAccount("http://other.example", "someone", "secret")
        val otherSource = PlaylistSource("xtream", "other.example", "someone")
        val incoming = XtreamChannels.load(otherAccount, listOf(XtreamStream("10", "Cooking TV", "1"), XtreamStream("77", "Das Erste HD", "1", "ard")), mapOf("1" to "News"), emptyList()).channels
        val result = ChannelImport.merge(saved, source, emptyList(), otherSource, incoming, ChannelImport.Options(matchById = false, useStreamKeys = true) { true })

        val cooking = result.channels.single { it.name == "Cooking TV" }
        assertFalse(cooking.favorite)
        assertEquals("http://other.example/live/someone/secret/10.ts", cooking.url)
        // Same name in the same category is the one thing that may carry state across.
        val ard = result.channels.single { it.name == "Das Erste HD" }
        assertTrue(ard.favorite)
        assertEquals("http://other.example/live/someone/secret/77.ts", ard.url)
        assertEquals(setOf("9f3ab1c2d4", "ard#aa11"), result.archive.map { it.channel.id }.toSet())

        // Going back to the first playlist restores what was archived, by stream number.
        val home = ChannelImport.merge(result.channels, otherSource, result.archive, source, apiChannels(Triple(11, "No Guide", ""), Triple(12, "Das Erste FHD", "ard")), api)
        assertTrue(home.channels.single { it.id == "9f3ab1c2d4" }.hidden)
        assertEquals("ARD", home.channels.single { it.id == "ard#aa11" }.customName)
    }

    @Test
    fun renumberedStreamsFallBackToNamesInsteadOfSwappingFavourites() {
        // The provider rebuilt its panel: every stream number now belongs to another channel.
        val incoming = apiChannels(Triple(10, "No Guide", ""), Triple(11, "Das Erste FHD", "ard"), Triple(12, "Das Erste HD", "ard"))
        val result = ChannelImport.merge(saved, source, emptyList(), source, incoming, api)
        assertTrue(result.channels.single { it.name == "Das Erste HD" }.favorite)
        assertTrue(result.channels.single { it.name == "No Guide" }.hidden)
        assertEquals(live(12), result.channels.single { it.name == "Das Erste HD" }.url)
        assertEquals(0, result.added)
    }

    @Test
    fun aRenamedChannelIsStillRecognisedByItsStreamNumber() {
        val incoming = apiChannels(Triple(10, "ARD Das Erste", "ard"), Triple(11, "No Guide", ""), Triple(12, "Das Erste FHD", "ard"))
        val result = ChannelImport.merge(saved, source, emptyList(), source, incoming, api)
        assertTrue(result.channels.single { it.name == "ARD Das Erste" }.favorite)
    }

    @Test
    fun aRenewedLoginKeepsFavouritesButRebuildsStreamAddresses() {
        val renewed = XtreamAccount("http://tv.example:8080", "user2", "pass2")
        val renewedSource = PlaylistSource("xtream", "tv.example", "user2")
        assertTrue(source.sameAs(renewedSource))
        val incoming = XtreamChannels.load(renewed, listOf(XtreamStream("10", "Das Erste HD", "1", "ard")), mapOf("1" to "News"), saved).channels
        val result = ChannelImport.merge(saved, source, emptyList(), renewedSource, incoming, ChannelImport.Options(matchById = false, useStreamKeys = true) { XtreamChannels.savedUrlStillValid(renewed, "http://tv.example:8080", it.url) })
        assertTrue(result.channels.single().favorite)
        assertEquals("http://tv.example:8080/live/user2/pass2/10.ts", result.channels.single().url)
    }

    @Test
    fun playlistFallbackRecognisesChannelsImportedThroughTheApi() {
        val viaApi = ChannelImport.merge(emptyList(), source, emptyList(), source, apiChannels(Triple(5, "One", ""), Triple(6, "Two", "two")), api).channels
            .map { if (it.name == "One") it.copy(favorite = true) else it }
        val parsed = M3uParser.parse("#EXTINF:-1 group-title=\"News\",One\nhttp://tv.example:8080/user/pass/5\n#EXTINF:-1 tvg-id=\"two\" group-title=\"News\",Two\nhttp://tv.example:8080/user/pass/6")
        val result = ChannelImport.merge(viaApi, source, emptyList(), source, parsed, playlist)
        assertEquals(viaApi.map { it.id }, result.channels.map { it.id })
        assertTrue(result.channels[0].favorite)
        assertEquals("http://tv.example:8080/user/pass/5", result.channels[0].url)
    }

    @Test
    fun plainPlaylistsKeepStateWhenStreamAddressesChange() {
        val m3u = PlaylistSource("m3u", "lists.example")
        val before = M3uParser.parse("#EXTINF:-1 group-title=\"News\",One\nhttps://cdn/one/index.m3u8?token=a").map { it.copy(favorite = true) }
        val after = M3uParser.parse("#EXTINF:-1 group-title=\"News\",One\nhttps://cdn/one/index.m3u8?token=b")
        val result = ChannelImport.merge(before, m3u, emptyList(), m3u, after, ChannelImport.Options(matchById = true, useStreamKeys = false))
        assertTrue(result.channels.single().favorite)
        assertEquals("https://cdn/one/index.m3u8?token=b", result.channels.single().url)
        assertEquals(before.single().id, result.channels.single().id)
    }

    @Test
    fun sourcesMatchOnHostOrLoginButNotOnNeither() {
        assertTrue(source.sameAs(PlaylistSource("xtream", "new-domain.example", "user")))
        assertTrue(source.sameAs(PlaylistSource("m3u", "TV.example")))
        assertFalse(source.sameAs(PlaylistSource("xtream", "other.example", "someone")))
        assertEquals(source, PlaylistSource.decode(source.encode()))
        assertEquals(source, PlaylistSource.of(ProviderConfig(ProviderType.XTREAM, "x", serverUrl = "http://tv.example:8080", username = "user", password = "pass")))
    }
}
