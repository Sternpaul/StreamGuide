package com.sternpaul.streamguide.core

data class TimelineSlice(
    val program: Program?,
    val visibleStart: Long,
    val visibleEnd: Long
)

object GuideTimeline {
    fun slices(
        channel: Channel,
        programs: List<Program>,
        windowStart: Long,
        windowEnd: Long
    ): List<TimelineSlice> {
        require(windowEnd > windowStart) { "Timeline window must have positive duration" }
        val ids = setOf(channel.tvgId, channel.id).filter { it.isNotBlank() }.toSet()
        val matching = programs.asSequence()
            .filter { it.channelId in ids && it.endEpochMs > windowStart && it.startEpochMs < windowEnd }
            .sortedBy { it.startEpochMs }
            .toList()
        val result = mutableListOf<TimelineSlice>()
        var cursor = windowStart
        for (program in matching) {
            val start = maxOf(cursor, windowStart, program.startEpochMs)
            val end = minOf(windowEnd, program.endEpochMs)
            if (start > cursor) result += TimelineSlice(null, cursor, start)
            if (end > start) {
                result += TimelineSlice(program, start, end)
                cursor = end
            }
        }
        if (cursor < windowEnd) result += TimelineSlice(null, cursor, windowEnd)
        return result
    }
}
