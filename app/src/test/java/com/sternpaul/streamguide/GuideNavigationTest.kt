package com.sternpaul.streamguide

import com.sternpaul.streamguide.core.Channel
import org.junit.Assert.*
import org.junit.Test

class GuideNavigationTest {
    private val channels = listOf(
        Channel("news1", "News 1", "https://example.test/1", group = "News"),
        Channel("news2", "News 2", "https://example.test/2", group = "News"),
        Channel("sport", "Sport", "https://example.test/3", group = "Sport")
    )
    private fun newsState() = UiState(channels = channels, selectedGroup = "News", selectedChannelId = "news2")

    @Test fun switchingAwayAndBackRestoresChannel() {
        val sport = GuideNavigation.selectGroup(newsState(), "Sport")
        assertEquals("sport", sport.selectedChannelId)
        assertEquals("news2", GuideNavigation.selectGroup(sport, "News").selectedChannelId)
    }

    @Test fun reselectingOpenCategoryDoesNotResetChannel() {
        assertEquals("news2", GuideNavigation.selectGroup(newsState(), "News").selectedChannelId)
    }

    @Test fun hiddenRememberedChannelFallsBackToVisibleChannel() {
        val sport = GuideNavigation.selectGroup(newsState(), "Sport")
            .copy(channels = channels.map { if (it.id == "news2") it.copy(hidden = true) else it })
        assertEquals("news1", GuideNavigation.selectGroup(sport, "News").selectedChannelId)
    }

    @Test fun removedChannelAndEmptyCategoryDoNotKeepInvalidSelection() {
        val sport = GuideNavigation.selectGroup(newsState(), "Sport").copy(channels = channels.take(1))
        assertEquals("news1", GuideNavigation.selectGroup(sport, "News").selectedChannelId)
        assertNull(GuideNavigation.selectGroup(sport, "Favorites").selectedChannelId)
    }
}
