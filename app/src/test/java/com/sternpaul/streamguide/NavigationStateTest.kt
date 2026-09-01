package com.sternpaul.streamguide

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationStateTest {
    @Test
    fun liveTvIsTheDefaultScreenAndMenusStartClosed() {
        val state = UiState()
        assertEquals(AppScreen.GUIDE, state.screen)
        assertEquals(OverlayMenu.NONE, state.overlayMenu)
    }

    @Test
    fun navigatingFromTheAppMenuAlwaysClosesTheOverlay() {
        assertEquals(
            OverlayMenu.NONE,
            NavigationPolicy.afterDestinationSelected()
        )
    }

    @Test
    fun remoteOptionsOnlyOpensChannelActionsInLiveTv() {
        assertEquals(OverlayMenu.CHANNEL, NavigationPolicy.onOptionsPressed(AppScreen.GUIDE))
        assertEquals(OverlayMenu.NONE, NavigationPolicy.onOptionsPressed(AppScreen.SEARCH))
        assertEquals(OverlayMenu.NONE, NavigationPolicy.onOptionsPressed(AppScreen.SETTINGS))
    }
}
