package com.sternpaul.streamguide.core

enum class StartupRefreshAction { NONE, EPG_ONLY, FULL_PLAYLIST }

object RefreshPolicy {
    fun canReuseRecentEpg(force: Boolean, lastSuccess: Long, now: Long): Boolean =
        !force && lastSuccess > 0 && now - lastSuccess in 0 until 60_000L

    fun onAppStart(
        hasProvider: Boolean,
        playlistOnStart: Boolean
    ): StartupRefreshAction = when {
        !hasProvider -> StartupRefreshAction.NONE
        playlistOnStart -> StartupRefreshAction.FULL_PLAYLIST
        else -> StartupRefreshAction.EPG_ONLY
    }
}
