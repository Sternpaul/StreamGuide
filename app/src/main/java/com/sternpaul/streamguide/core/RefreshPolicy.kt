package com.sternpaul.streamguide.core

enum class StartupRefreshAction { NONE, EPG_ONLY, FULL_PLAYLIST }

object RefreshPolicy {
    fun onAppStart(
        hasProvider: Boolean,
        playlistOnStart: Boolean,
        epgOnStart: Boolean,
        epgIsStale: Boolean
    ): StartupRefreshAction = when {
        !hasProvider -> StartupRefreshAction.NONE
        playlistOnStart -> StartupRefreshAction.FULL_PLAYLIST
        epgOnStart && epgIsStale -> StartupRefreshAction.EPG_ONLY
        else -> StartupRefreshAction.NONE
    }
}
