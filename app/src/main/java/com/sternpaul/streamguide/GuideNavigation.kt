package com.sternpaul.streamguide

import com.sternpaul.streamguide.core.Channel

object GuideNavigation {
    fun selectGroup(state: UiState, group: String): UiState {
        val previousGroup = state.selectedGroup
        val remembered = state.selectedChannelByGroup.toMutableMap()
        val previousChannel = state.selectedChannelId
        if (previousChannel != null && state.channels.any { it.id == previousChannel && it.isVisibleInGroup(previousGroup) }) {
            remembered[previousGroup] = previousChannel
        }
        val selected = remembered[group]?.takeIf { id -> state.channels.any { it.id == id && it.isVisibleInGroup(group) } }
            ?: state.channels.firstOrNull { it.isVisibleInGroup(group) }?.id
        return state.copy(
            selectedGroup = group,
            focusedGroup = group,
            optionsContext = OptionsContext.GROUP,
            favoritesOnly = group == "Favorites",
            selectedChannelId = selected,
            selectedProgram = null,
            selectedChannelByGroup = remembered
        )
    }

    private fun Channel.isVisibleInGroup(group: String): Boolean = !hidden && when (group) {
        "All channels" -> true
        "Favorites" -> favorite
        else -> displayGroup == group
    }
}
