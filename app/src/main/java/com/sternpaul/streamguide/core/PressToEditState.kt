package com.sternpaul.streamguide.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Keeps TV text fields navigable until the user explicitly presses Select. */
class PressToEditState {
    var isEditing by mutableStateOf(false)
        private set

    val isReadOnly: Boolean
        get() = !isEditing

    fun onFocusChanged(isFocused: Boolean) {
        if (!isFocused) isEditing = false
    }

    fun onPress(): Boolean {
        if (isEditing) return false
        isEditing = true
        return true
    }
}
