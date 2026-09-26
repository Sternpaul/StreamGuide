package com.sternpaul.streamguide.core

object RecentChannels {
    const val limit = 30
    fun record(previous: List<String>, channelId: String): List<String> =
        (listOf(channelId) + previous).distinct().take(limit)
}
