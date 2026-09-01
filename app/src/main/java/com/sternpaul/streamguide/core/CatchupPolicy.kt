package com.sternpaul.streamguide

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

object CatchupPolicy {
    const val supportedDays = setOf(1, 2, 3, 7, 14, 30)
    const val maxCatchupSpanDays = 30

    fun isValidDays(days: Int): Boolean = days in supportedDays

    fun catchupSourceFor(channel: Channel, program: Program): String? {
        if (channel.catchupDays <= 0) return null
        if (channel.catchupSource.isNotBlank()) return channel.catchupSource
        val effectiveDays = minOf(channel.catchupDays, maxCatchupSpanDays)
        return "{utc}"  // default template; expanded by provider fallback
    }
}