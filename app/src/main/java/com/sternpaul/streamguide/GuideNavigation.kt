package com.sternpaul.streamguide

object GuideNavigation {
    fun selectGroup(state: UiState, group: String): UiState {
        val remembered = state.selectedChannelByGroup.toMutableMap()
        state.selectedChannelId?.takeIf { id -> state.visibleChannels.any { it.id == id } }?.let {
            remembered[state.selectedGroup] = it
        }
        val target = state.copy(
            selectedGroup = group,
            focusedGroup = group,
            optionsContext = OptionsContext.GROUP,
            favoritesOnly = group == "Favorites",
            selectedProgram = null,
            selectedChannelByGroup = remembered
        )
        val selected = remembered[group]?.takeIf { id -> target.visibleChannels.any { it.id == id } }
            ?: target.visibleChannels.firstOrNull()?.id
        return target.copy(selectedChannelId = selected)
    }
}
