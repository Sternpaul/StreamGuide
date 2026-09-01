package com.sternpaul.streamguide

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteInteractionPolicyTest {
    @Test
    fun firstBackOnLiveTvArmsExitAndSecondBackExits() {
        assertEquals(BackAction.ARM_EXIT, BackNavigationPolicy.action(AppScreen.GUIDE, exitArmed = false))
        assertEquals(BackAction.EXIT, BackNavigationPolicy.action(AppScreen.GUIDE, exitArmed = true))
    }

    @Test
    fun backFromSecondaryScreenReturnsToLiveTv() {
        assertEquals(BackAction.GO_TO_LIVE_TV, BackNavigationPolicy.action(AppScreen.SETTINGS, exitArmed = false))
    }

    @Test
    fun playerRemoteKeysMapToRealPlaybackActions() {
        assertEquals(PlayerRemoteAction.SEEK_BACK, PlayerRemotePolicy.action(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PlayerRemoteAction.SEEK_FORWARD, PlayerRemotePolicy.action(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(PlayerRemoteAction.PLAY_PAUSE, PlayerRemotePolicy.action(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(PlayerRemoteAction.PREVIOUS_CHANNEL, PlayerRemotePolicy.action(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(PlayerRemoteAction.NEXT_CHANNEL, PlayerRemotePolicy.action(KeyEvent.KEYCODE_DPAD_DOWN))
    }
}
