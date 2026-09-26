package com.sternpaul.streamguide

import com.sternpaul.streamguide.core.Channel
import com.sternpaul.streamguide.core.Program
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchScopeTest {
    private val channels = listOf(
        Channel("news", "World News", "https://example.test/news", group = "News", favorite = true),
        Channel("sport", "World Sport", "https://example.test/sport", group = "Sport", tvgId = "sport-epg"),
        Channel("hidden", "World Hidden", "https://example.test/hidden", group = "Other", hidden = true)
    )

    @Test fun launchDefaultsToVisibleFavorites() {
        assertEquals(listOf("news"), UiState(channels = channels).visibleChannels.map { it.id })
    }

    @Test fun channelSearchIgnoresBothCategoryAndFavoritesFilters() {
        for (group in listOf("Favorites", "News", "Sport")) {
            val state = UiState(screen = AppScreen.SEARCH, channels = channels,
                selectedGroup = group, favoritesOnly = true, query = "world")
            assertEquals(listOf("news", "sport"), state.visibleChannels.map { it.id })
        }
    }

    @Test fun emptySearchShowsAllVisibleChannels() {
        val state = UiState(screen = AppScreen.SEARCH, channels = channels, favoritesOnly = true)
        assertEquals(listOf("news", "sport"), state.visibleChannels.map { it.id })
    }

    @Test fun programmeMatchesOutsideTheOpenCategoryAreIncluded() {
        val state = UiState(screen = AppScreen.SEARCH, channels = channels, selectedGroup = "News",
            query = "Championship", programSearchChannelIds = setOf("sport-epg"))
        assertEquals(listOf("sport"), state.visibleChannels.map { it.id })
        val cached = state.copy(programSearchChannelIds = emptySet(),
            programIndex = ProgramIndex(listOf(Program("sport-epg", "Championship", startEpochMs = 1, endEpochMs = 2))))
        assertEquals(listOf("sport"), cached.visibleChannels.map { it.id })
    }

    @Test fun leavingSearchRestoresCategoryWithoutItsQueryFilteringTheGuide() {
        val search = UiState(screen = AppScreen.SEARCH, channels = channels, selectedGroup = "News", query = "Sport")
        assertEquals(listOf("sport"), search.visibleChannels.map { it.id })
        assertEquals(listOf("news"), search.copy(screen = AppScreen.GUIDE).visibleChannels.map { it.id })
    }

    @Test fun channelSwitchingDuringSearchPlaybackUsesGlobalResults() {
        val state = UiState(screen = AppScreen.PLAYER, playbackSourceScreen = AppScreen.SEARCH,
            channels = channels, selectedGroup = "Favorites", query = "world")
        assertEquals(listOf("news", "sport"), state.visibleChannels.map { it.id })
    }
}
