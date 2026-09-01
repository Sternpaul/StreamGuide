package com.sternpaul.streamguide.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressToEditStateTest {
    @Test
    fun focusingFieldDoesNotStartEditing() {
        val state = PressToEditState()

        state.onFocusChanged(true)

        assertFalse(state.isEditing)
        assertTrue(state.isReadOnly)
    }

    @Test
    fun pressingFocusedFieldStartsEditing() {
        val state = PressToEditState()
        state.onFocusChanged(true)

        val consumed = state.onPress()

        assertTrue(consumed)
        assertTrue(state.isEditing)
        assertFalse(state.isReadOnly)
    }

    @Test
    fun leavingFieldEndsEditing() {
        val state = PressToEditState()
        state.onFocusChanged(true)
        state.onPress()

        state.onFocusChanged(false)

        assertFalse(state.isEditing)
        assertTrue(state.isReadOnly)
    }
}
