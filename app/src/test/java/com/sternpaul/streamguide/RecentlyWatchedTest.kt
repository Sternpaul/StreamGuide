package com.sternpaul.streamguide

import com.sternpaul.streamguide.core.Channel
import com.sternpaul.streamguide.core.RecentChannels
import org.junit.Assert.*
import org.junit.Test

class RecentlyWatchedTest {
    private val channels = listOf(
        Channel("one", "A channel", "https://example.test/1", group = "News", favorite = true),
        Channel("two", "Z channel", "https://example.test/2", group = "Sports"),
        Channel("hidden", "Hidden", "https://example.test/3", group = "Other", hidden = true)
    )
    private fun recentState() = UiState(channels = channels, selectedGroup = "Recently watched",
        recentChannelIds = listOf("two", "hidden", "missing", "one", "two"))

    @Test fun favoritesAndRecentStayFirstRegardlessOfSavedCategoryOrder() {
        val state = recentState().copy(groupOrder = listOf("Sports", "Recently watched", "All channels", "Favorites", "News"))
        assertEquals(listOf("Favorites", "Recently watched", "Sports", "News"), state.groups)
        assertFalse(state.groups.contains("All channels"))
    }
    @Test fun historyIsNewestFirstWithoutHiddenMissingOrDuplicateChannels() {
        val state = recentState()
        assertEquals(listOf("two", "one"), state.visibleChannels.map { it.id })
        assertEquals(2, state.channelCountForGroup("Recently watched"))
        assertEquals(listOf("two", "one"), state.copy(sort = ChannelSort.ALPHABETICAL).visibleChannels.map { it.id })
    }
    @Test fun enteringRecentlyWatchedSelectsNewestAndRemembersPreviousFocus() {
        val initial = recentState().copy(selectedGroup = "Favorites", selectedChannelId = "one")
        val recent = GuideNavigation.selectGroup(initial, "Recently watched")
        assertEquals("two", recent.selectedChannelId)
        val favorites = GuideNavigation.selectGroup(recent.copy(selectedChannelId = "one"), "Favorites")
        assertEquals("one", GuideNavigation.selectGroup(favorites, "Recently watched").selectedChannelId)
    }
    @Test fun repeatViewingMovesChannelToTopAndBoundsSavedHistory() {
        assertEquals(listOf("one", "two"), RecentChannels.record(listOf("two", "one", "two"), "one"))
        val history = RecentChannels.record((1..40).map(Int::toString), "new")
        assertEquals(30, history.size)
        assertEquals("new", history.first())
        assertEquals("29", history.last())
    }
    @Test fun emptyHistoryStaysEmptyAndSearchStillIncludesUnwatchedChannels() {
        val state = recentState().copy(recentChannelIds = emptyList())
        assertTrue(state.visibleChannels.isEmpty())
        assertEquals(0, state.channelCountForGroup("Recently watched"))
        assertEquals(listOf("one", "two"), state.copy(screen = AppScreen.SEARCH).visibleChannels.map { it.id })
    }
}
