package com.sternpaul.streamguide

import android.view.KeyEvent

enum class BackAction { GO_TO_LIVE_TV, GO_TO_SETTINGS, ARM_EXIT, EXIT }

object BackNavigationPolicy {
    fun action(screen: AppScreen, exitArmed: Boolean): BackAction = when {
        screen == AppScreen.DIAGNOSTICS -> BackAction.GO_TO_SETTINGS
        screen != AppScreen.GUIDE -> BackAction.GO_TO_LIVE_TV
        exitArmed -> BackAction.EXIT
        else -> BackAction.ARM_EXIT
    }
}

object ScreenAwakePolicy {
    fun keepScreenOn(screen: AppScreen): Boolean = screen == AppScreen.PLAYER
}

enum class PlayerRemoteAction { NONE, PREVIOUS_CHANNEL, NEXT_CHANNEL, SEEK_BACK, SEEK_FORWARD, PLAY_PAUSE, PLAY, PAUSE, TOGGLE_OVERLAY }

object PlayerRemotePolicy {
    fun action(keyCode: Int): PlayerRemoteAction = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> PlayerRemoteAction.PREVIOUS_CHANNEL
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> PlayerRemoteAction.NEXT_CHANNEL
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> PlayerRemoteAction.SEEK_BACK
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> PlayerRemoteAction.SEEK_FORWARD
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PlayerRemoteAction.PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY -> PlayerRemoteAction.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> PlayerRemoteAction.PAUSE
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> PlayerRemoteAction.TOGGLE_OVERLAY
        else -> PlayerRemoteAction.NONE
    }
}
