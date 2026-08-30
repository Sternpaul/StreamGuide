package com.sternpaul.streamguide.core

object CatchupUrl {
    fun forProgram(channel: Channel, program: Program): String? {
        if (channel.catchupSource.isBlank() || channel.catchupDays <= 0 || program.endEpochMs <= program.startEpochMs) return null
        val start = program.startEpochMs / 1000
        val end = program.endEpochMs / 1000
        val duration = end - start
        return channel.catchupSource
            .replace("{utc}", start.toString())
            .replace("{utcend}", end.toString())
            .replace("{start}", start.toString())
            .replace("{end}", end.toString())
            .replace("{duration}", duration.toString())
            .replace("${'$'}{start}", start.toString())
            .replace("${'$'}{end}", end.toString())
            .replace("${'$'}{duration}", duration.toString())
    }
}
