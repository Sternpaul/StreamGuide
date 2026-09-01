package com.sternpaul.streamguide

import com.sternpaul.streamguide.core.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

class CategorySupportTest {
    @Test
    fun providerCategoriesAppearAndFilterChannels() {
        val state = UiState(
            channels = listOf(
                Channel("news-1", "News One", "https://live/1", group = "UK News"),
                Channel("sport-1", "Sport One", "https://live/2", group = "Sports"),
                Channel("news-2", "News Two", "https://live/3", group = "UK News")
            ),
            selectedGroup = "UK News"
        )

        assertEquals(listOf("All channels", "Favorites", "Sports", "UK News"), state.groups)
        assertEquals(listOf("news-1", "news-2"), state.visibleChannels.map { it.id })
    }
}
