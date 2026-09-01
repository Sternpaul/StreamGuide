package com.sternpaul.streamguide.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Keeps TV text fields navigable until the user explicitly presses Select.
 *  API:
 *    - call onPress() in response to a Select key event
 *    - isReadOnly is true until onPress() has been called
 *    - keyboard opens only after onPress() has been called
 */
class PressToEditState {
    var isEditing by mutableStateOf(false)
        private set

    val isReadOnly: Boolean
        get() = !isEditing

    /** Must be called when the user presses Select while the field is focused.
     *  After this, isEditing=true and the keyboard will appear. */
    fun onPress(): Boolean {
        if (isEditing) return false
        isEditing = true
        return true
    }

    /** Called when focus changes.  Clears editing state if focus leaves,
     *  so the field can be re-edited later. */
    fun onFocusChanged(isFocused: Boolean) {
        if (!isFocused) isEditing = false
    }
}